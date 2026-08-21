package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayMode;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayUnits;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbdbAircraftData;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbdbRouteData;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftEnrichment;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AircraftInfoClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.NearbyAircraft;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.text.Normalizer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class AircraftScreenService implements FrameScreenService<AircraftScreenConfig>,
        CommandScreenService<AircraftScreenConfig> {

    public static final int MIN_RADIUS_NM = 5;
    public static final int MAX_RADIUS_NM = 250;
    public static final int DEFAULT_FRAME_DELAY_MS = 100;

    /**
     * Target sweep revolution period, independent of the slot length. The radar frame
     * sequence spans exactly {@link #SWEEP_LOOP_REVOLUTIONS} whole revolutions starting
     * from a fixed base angle, so it ends exactly where it started: the panel replays
     * the uploaded frames modulo for the whole slot, and the loop restart is invisible.
     * A wall-clock phase does NOT work here: playback starts only after the upload
     * completes, at a variable delay from render time.
     */
    static final long SWEEP_REVOLUTION_MS = 4000;

    /**
     * Whole sweep revolutions per radar loop: the loop covers one full info-page cycle
     * ({@code SWEEP_LOOP_REVOLUTIONS} × {@link #SWEEP_REVOLUTION_MS}), so both the sweep
     * and the alternating route page complete whole cycles inside the loop.
     */
    static final int SWEEP_LOOP_REVOLUTIONS = 2;

    /**
     * The radar's right-hand info column is 30 px wide — too narrow for everything
     * known about the tracked aircraft — so it cycles through detail pages:
     * telemetry (distance/altitude/speed), route (origin/destination/owner) and
     * registry (country/manufacturer/type), for the closest aircraft only.
     */
    enum RadarInfoPage {
        TELEMETRY, ROUTE, REGISTRY
    }

    /**
     * Slowest feasible radar refresh cadence: SWEEP's {@code speedDegPerSec} is a u8
     * (&le;255&deg;/s), so one whole revolution per refresh needs at least
     * {@code ceil(360000 / 255)} ms.
     */
    static final long MIN_RADAR_REFRESH_MS = (long) Math.ceil(360_000.0 / 255);

    // Radar scope geometry: 32x32 square in the left half, right half is an info column.
    static final int SCOPE_CX = 16;
    static final int SCOPE_CY = 16;
    static final int SCOPE_RADIUS = 14;
    static final int INFO_X = 34;

    static final Color SCOPE_GREEN = new Color(0, 90, 0);
    static final Color SCOPE_RING = new Color(0, 50, 0);
    static final Color SWEEP_TRAIL = new Color(0, 60, 0);
    static final Color SWEEP_LEAD = new Color(0, 255, 0);

    /* --------------------------------------------------------------------
     * ACMD command path (plan §6): font page ids shared with the other
     * command screens (batches are self-contained, this is convention only).
     * ------------------------------------------------------------------ */
    static final int PAGE_LEDBOARD_ID = 0;
    static final int PAGE_CGPIXEL_ID = 1;

    /** SCROLL pace for lines that overflow the canvas (ms per pixel, ~17 px/s). */
    static final int SCROLL_MS_PER_PX = 60;

    /** SCROLL clip window height: one cgPixel text row plus a pixel of clearance. */
    static final int SCROLL_REGION_HEIGHT = 8;

    /** GFX ring radii standing in for the frame path's AWT ovals (boxes 16 and 14). */
    static final int SCOPE_RING_MID_RADIUS = 8;
    static final int SCOPE_RING_INNER_RADIUS = 7;

    /**
     * ACMD radar live-refresh cadence: the command batch (blips + info column) is
     * re-rendered from a fresh aircraft fetch and republished every 2000 ms for the
     * whole slot — the frame path's whole-loop upload replays frozen data instead.
     */
    static final long RADAR_REFRESH_MS = 2000;

    /**
     * ACMD SWEEP speed of the single-batch fallback path: one whole revolution per
     * {@link #RADAR_REFRESH_MS} (2000 ms &rarr; 180&deg;/s). The live refresh path
     * instead derives the speed from the slot's page-tiled cadence
     * ({@link #radarSweepDegPerSec}), keeping the one-revolution-per-refresh
     * invariant that makes each republish re-arm the sweep exactly where the
     * previous one wrapped.
     */
    static final int SWEEP_DEG_PER_SEC = (int) Math.round(360_000.0 / RADAR_REFRESH_MS);

    /**
     * Refresh cadence for a radar slot: the largest grid &le;{@link #RADAR_REFRESH_MS}
     * that tiles the slot into exactly {@code pageCount} equal page windows (a whole
     * number of refreshes per page), so no page — least of all the rotation's last —
     * is truncated by the slot end. A fixed dwell cannot do this (10 s over 3 pages
     * would show 4/4/2 s); the cadence is what must adapt, because a page swap can
     * only land on a refresh publish. Falls back to one refresh per page — even if
     * that exceeds {@code RADAR_REFRESH_MS} — when the u8 sweep-speed cap makes the
     * tighter grid infeasible, and never drops below {@link #MIN_RADAR_REFRESH_MS}.
     * A single-page column keeps the plain {@link #RADAR_REFRESH_MS} cadence.
     */
    static long radarRefreshMs(final long slotMillis, final int pageCount) {
        if (pageCount <= 1 || slotMillis <= 0) {
            return RADAR_REFRESH_MS;
        }
        final int refreshesPerPage = Math.max(1,
                (int) Math.ceil(slotMillis / (double) (pageCount * RADAR_REFRESH_MS)));
        final long tiled = slotMillis / ((long) pageCount * refreshesPerPage);
        if (tiled >= MIN_RADAR_REFRESH_MS) {
            return tiled;
        }
        return Math.max(slotMillis / pageCount, MIN_RADAR_REFRESH_MS);
    }

    /**
     * Whole refreshes per info page for a cadence from {@link #radarRefreshMs} (the
     * inverse of its integer division, so the two stay consistent): page k occupies
     * the grid slice {@code [k·rpp·refreshMs, (k+1)·rpp·refreshMs)}, the last page
     * holding through the slot's tail.
     */
    static int radarRefreshesPerPage(final long slotMillis, final int pageCount, final long refreshMs) {
        return Math.max(1, (int) Math.round((double) slotMillis / ((long) pageCount * refreshMs)));
    }

    /** SWEEP speed for a refresh cadence: exactly one whole revolution per refresh, u8-clamped. */
    static int radarSweepDegPerSec(final long refreshMs) {
        return (int) Math.max(1, Math.min(255, Math.round(360_000.0 / refreshMs)));
    }

    final PaintToolsService paintToolsService;
    final Font ledBoardFont8Px;
    final Font cgPixel5Px;
    final AircraftClient aircraftClient;
    final AircraftInfoClient aircraftInfoClient;

    /** Extracted FONT pages (deterministic per TTF+size), computed on first command render. */
    private volatile FontPageExtractor.FontPage ledBoardPage;
    private volatile FontPageExtractor.FontPage cgPixelPage;

    @Override
    public ScreenType getScreenType() {
        return ScreenType.NEARBY_AIRCRAFT;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final AircraftScreenConfig screenConfig) {
        if (screenConfig.producesFrames()) {
            final List<BufferedImage> frames = renderFrames(screenConfig);
            return frames.isEmpty() ? Optional.empty() : Optional.of(frames.getFirst());
        }

        final List<NearbyAircraft> aircraft = getAircraft(screenConfig);
        return Optional.of(switch (screenConfig.displayMode()) {
            case CLOSEST -> aircraft.isEmpty()
                    ? noAircraftPage()
                    : closestIdentityPage(aircraft.getFirst(), screenConfig.units());
            case LIST -> renderList(aircraft, screenConfig.units());
            case RADAR -> renderRadarStatic(aircraft, screenConfig);
        });
    }

    @Override
    public List<BufferedImage> renderFrames(final AircraftScreenConfig screenConfig) {
        if (!screenConfig.producesFrames()) {
            return List.of();
        }

        final List<NearbyAircraft> aircraft = getAircraft(screenConfig);
        return screenConfig.displayMode() == AircraftDisplayMode.RADAR
                ? radarFrames(aircraft, screenConfig)
                : closestStream(aircraft, screenConfig).frames();
    }

    @Override
    public FrameStream renderFrameStream(final AircraftScreenConfig screenConfig) {
        if (screenConfig.producesFrames() && screenConfig.displayMode() == AircraftDisplayMode.CLOSEST) {
            return closestStream(getAircraft(screenConfig), screenConfig);
        }
        return FrameScreenService.super.renderFrameStream(screenConfig);
    }

    /* --------------------------------------------------------------------
     * RADAR frames
     * ------------------------------------------------------------------ */

    /**
     * Radar loop frame count: {@link #SWEEP_LOOP_REVOLUTIONS} whole sweep revolutions
     * covering one full info-page cycle, one frame per delay tick — the slot duration
     * no longer drives it, because the panel replays the frames modulo for the whole
     * slot. Clamped to the protocol's 2–200 frame limits.
     */
    static int radarFrameCount(final int frameDelayMs) {
        return Math.max(2, Math.min(200,
                (int) (SWEEP_LOOP_REVOLUTIONS * SWEEP_REVOLUTION_MS / frameDelayMs)));
    }

    /**
     * Sweep angle in integer degrees of 0-based frame {@code frameIndex}: integer steps
     * of {@code 360 × SWEEP_LOOP_REVOLUTIONS / frameCount}, so the last frame lands on
     * an exact multiple of 360° and the wrap stays continuous across the modulo loop
     * boundary (the sweep starts and ends at the same angle).
     */
    static int radarSweepDeg(final int frameIndex, final int frameCount) {
        return (frameIndex + 1) * 360 * SWEEP_LOOP_REVOLUTIONS / frameCount;
    }

    /**
     * Info-page index of 0-based frame {@code frameIndex}: the loop's frames spread
     * evenly over the detail pages (floor division), so the page cycle completes
     * exactly once per loop and the modulo replay restarts it in phase (each cycle
     * opens on page 0 = telemetry).
     */
    static int radarInfoPage(final int frameIndex, final int frameCount, final int pageCount) {
        return Math.min(pageCount - 1, (int) ((long) frameIndex * pageCount / frameCount));
    }

    /**
     * Info-page index {@code elapsedMs} into a radar slot (command path): grid-derived
     * — the refresh index divided by the page width in grid points — so dropped
     * refreshes never desync the rotation. Clamped to the last page: the cadence
     * ({@link #radarRefreshMs}) tiles the slot into exactly one page cycle, and the
     * last page holds through the slot's tail instead of wrapping mid-slot.
     */
    static int radarPageIndex(final long elapsedMs, final long refreshMs,
                              final int refreshesPerPage, final int pageCount) {
        return (int) Math.min(pageCount - 1, elapsedMs / refreshMs / refreshesPerPage);
    }

    /** The detail pages the info column cycles through for the tracked aircraft. */
    private static List<RadarInfoPage> radarInfoPages(final AircraftEnrichment enrichment) {
        final var pages = new java.util.ArrayList<RadarInfoPage>(3);
        pages.add(RadarInfoPage.TELEMETRY);
        if (enrichment != null && (enrichment.hasRoute() || enrichment.owner() != null)) {
            pages.add(RadarInfoPage.ROUTE);
        }
        if (enrichment != null && (enrichment.ownerCountry() != null || enrichment.manufacturer() != null
                || enrichment.typeName() != null || enrichment.icaoType() != null)) {
            pages.add(RadarInfoPage.REGISTRY);
        }
        return pages;
    }

    private List<BufferedImage> radarFrames(final List<NearbyAircraft> aircraft,
                                            final AircraftScreenConfig screenConfig) {
        final int frameDelayMs = Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS, screenConfig.frameDelayMs());
        final int frameCount = radarFrameCount(frameDelayMs);

        final AircraftEnrichment enrichment = enrichClosest(aircraft);
        final List<RadarInfoPage> pages = radarInfoPages(enrichment);

        final var frames = new java.util.ArrayList<BufferedImage>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            frames.add(renderRadarFrame(aircraft, closestOrNull(aircraft), radarSweepDeg(i, frameCount),
                    screenConfig, enrichment, pages.get(radarInfoPage(i, frameCount, pages.size()))));
        }
        return frames;
    }

    /** The tracked radar aircraft — always the closest. Null when the sky is empty. */
    private static NearbyAircraft closestOrNull(final List<NearbyAircraft> aircraft) {
        return aircraft.isEmpty() ? null : aircraft.getFirst();
    }

    private List<NearbyAircraft> getAircraft(final AircraftScreenConfig screenConfig) {
        final LatLon latLon = screenConfig.latLon() != null ? screenConfig.latLon() : new LatLon(0, 0);
        final int radiusNm = Math.min(MAX_RADIUS_NM, Math.max(MIN_RADIUS_NM, screenConfig.radiusNm()));
        return aircraftClient.getAircraft(latLon, radiusNm, screenConfig.militaryOnly());
    }

    /* --------------------------------------------------------------------
     * RADAR
     * ------------------------------------------------------------------*/

    private BufferedImage renderRadarFrame(final List<NearbyAircraft> aircraft,
                                           final NearbyAircraft selected,
                                           final double sweepDeg,
                                           final AircraftScreenConfig screenConfig,
                                           final AircraftEnrichment enrichment,
                                           final RadarInfoPage infoPage) {
        final BufferedImage image = paintToolsService.newImage();
        drawScope(image);
        drawBlips(image, aircraft, sweepDeg, screenConfig);
        drawSelectionBox(image, selected, Math.max(1, screenConfig.radiusNm()));
        drawSweep(image, sweepDeg);
        drawInfoColumn(image, selected, screenConfig.units(), enrichment, infoPage);
        return image;
    }

    private BufferedImage renderRadarStatic(final List<NearbyAircraft> aircraft,
                                            final AircraftScreenConfig screenConfig) {
        return renderRadarFrame(aircraft, closestOrNull(aircraft), 0, screenConfig, null, RadarInfoPage.TELEMETRY);
    }

    private void drawScope(final BufferedImage image) {
        final Graphics2D g = (Graphics2D) image.getGraphics();
        g.setColor(SCOPE_RING);
        g.drawOval(SCOPE_CX - SCOPE_RADIUS / 2 - 1, SCOPE_CY - SCOPE_RADIUS / 2 - 1, SCOPE_RADIUS + 2, SCOPE_RADIUS + 2);
        g.setColor(SCOPE_GREEN);
        g.drawOval(SCOPE_CX - SCOPE_RADIUS, SCOPE_CY - SCOPE_RADIUS, SCOPE_RADIUS * 2, SCOPE_RADIUS * 2);
        g.setColor(SCOPE_RING);
        g.drawOval(SCOPE_CX - SCOPE_RADIUS / 2, SCOPE_CY - SCOPE_RADIUS / 2, SCOPE_RADIUS, SCOPE_RADIUS);
        g.setColor(SCOPE_GREEN);
        g.fillRect(SCOPE_CX, SCOPE_CY, 1, 1);
    }

    private void drawBlips(final BufferedImage image, final List<NearbyAircraft> aircraft,
                           final double sweepDeg, final AircraftScreenConfig screenConfig) {
        final Graphics2D g = (Graphics2D) image.getGraphics();
        final int radiusNm = Math.max(1, screenConfig.radiusNm());
        for (final NearbyAircraft a : aircraft) {
            final double r = Math.min(1.0, a.distanceNm() / radiusNm) * SCOPE_RADIUS;
            final double rad = Math.toRadians(a.bearingDeg());
            final int x = SCOPE_CX + (int) Math.round(r * Math.sin(rad));
            final int y = SCOPE_CY - (int) Math.round(r * Math.cos(rad));

            final Color color = altitudeColor(a);
            g.setColor(isSwept(a.bearingDeg(), sweepDeg) ? color : dim(color));
            g.fillRect(x - 1, y - 1, 2, 2);
        }
    }

    /**
     * The info column describes the currently tracked aircraft, but every blip carries
     * the same altitude-band color — mark the tracked one with a white 4×4 box around
     * its blip (drawn after the blips, never dimmed by the sweep decay: it is a
     * selection marker, not data). AWT drawRect spans w+1 px, so 3,3 covers the same
     * 4×4 as the command path's GFX RECT 4,4.
     */
    private void drawSelectionBox(final BufferedImage image, final NearbyAircraft selected, final int radiusNm) {
        if (selected == null) {
            return;
        }
        final double r = Math.min(1.0, selected.distanceNm() / radiusNm) * SCOPE_RADIUS;
        final double rad = Math.toRadians(selected.bearingDeg());
        final int x = SCOPE_CX + (int) Math.round(r * Math.sin(rad));
        final int y = SCOPE_CY - (int) Math.round(r * Math.cos(rad));
        final Graphics2D g = (Graphics2D) image.getGraphics();
        g.setColor(Color.WHITE);
        g.drawRect(x - 2, y - 2, 3, 3);
    }

    /** Blips light up as the sweep passes over them, then dim but stay visible. */
    private static boolean isSwept(final double bearingDeg, final double sweepDeg) {
        return (sweepDeg - bearingDeg + 360) % 360 < 90;
    }

    private static Color dim(final Color c) {
        return new Color(c.getRed() / 3, c.getGreen() / 3, c.getBlue() / 3);
    }

    private void drawSweep(final BufferedImage image, final double sweepDeg) {
        final Graphics2D g = (Graphics2D) image.getGraphics();
        final Color[] trail = {
                SWEEP_LEAD, new Color(0, 200, 0), new Color(0, 150, 0),
                new Color(0, 105, 0), new Color(0, 65, 0), SWEEP_TRAIL
        };
        for (int k = 0; k < trail.length; k++) {
            final double deg = sweepDeg - k * 6.0;
            final double rad = Math.toRadians(deg);
            final int x = SCOPE_CX + (int) Math.round(SCOPE_RADIUS * Math.sin(rad));
            final int y = SCOPE_CY - (int) Math.round(SCOPE_RADIUS * Math.cos(rad));
            g.setColor(trail[k]);
            g.drawLine(SCOPE_CX, SCOPE_CY, x, y);
        }
    }

    private void drawInfoColumn(final BufferedImage image, final NearbyAircraft selected,
                                final AircraftDisplayUnits units,
                                final AircraftEnrichment enrichment,
                                final RadarInfoPage infoPage) {
        if (selected == null) {
            paintToolsService.drawText(image, cgPixel5Px, "NO", INFO_X, 11, Color.RED);
            paintToolsService.drawText(image, cgPixel5Px, "ACFT", INFO_X, 19, Color.RED);
            return;
        }

        paintToolsService.drawText(image, cgPixel5Px, truncate(selected.callsign(), 6), INFO_X, 5, Color.WHITE);

        switch (infoPage) {
            case ROUTE -> {
                if (enrichment != null && enrichment.hasRoute()) {
                    paintToolsService.drawText(image, cgPixel5Px, truncate(enrichment.originCode(), 5), INFO_X, 12, Color.GREEN);
                    paintToolsService.drawText(image, cgPixel5Px, truncate(enrichment.destinationCode(), 5), INFO_X, 19, Color.CYAN);
                } else {
                    paintToolsService.drawText(image, cgPixel5Px, "ROUTE", INFO_X, 12, SCOPE_GREEN);
                    paintToolsService.drawText(image, cgPixel5Px, "UNK", INFO_X, 19, SCOPE_GREEN);
                }
                paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.owner()), 6), INFO_X, 26, Color.YELLOW);
            }
            case REGISTRY -> {
                paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.ownerCountry()), 6), INFO_X, 12, Color.CYAN);
                paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.manufacturer()), 6), INFO_X, 19, Color.WHITE);
                paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.displayType(6)), 6), INFO_X, 26, Color.GREEN);
            }
            case TELEMETRY -> {
                paintToolsService.drawText(image, cgPixel5Px, formatDistance(selected, units), INFO_X, 12, altitudeColor(selected));
                paintToolsService.drawText(image, cgPixel5Px, formatAltitude(selected, units), INFO_X, 19, Color.CYAN);
                paintToolsService.drawText(image, cgPixel5Px, formatSpeed(selected, units), INFO_X, 26, Color.YELLOW);
            }
        }
    }

    /* --------------------------------------------------------------------
     * CLOSEST card (cycling pages)
     *
     * Pages are spread equally across the slot: one frame per page, played back at
     * slot/pageCount. The delay therefore depends on the pages that actually exist
     * (aircraft present? enrichment available?) and is returned together with the
     * frames via renderFrameStream. A single page is emitted twice (protocol minimum
     * of 2 frames), trivially equal.
     * ------------------------------------------------------------------*/

    private FrameStream closestStream(final List<NearbyAircraft> aircraft,
                                      final AircraftScreenConfig screenConfig) {
        final List<BufferedImage> pages = buildClosestPages(aircraft, screenConfig.units());
        final long slotMillis = screenConfig.durationSeconds() * 1000L;

        final int frameCount = Math.max(2, pages.size());
        final long dwellMs = Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS,
                Math.min(65_535, slotMillis / frameCount));

        final var frames = new java.util.ArrayList<BufferedImage>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            frames.add(pages.get(i % pages.size()));
        }
        return new FrameStream(frames, dwellMs);
    }

    private List<BufferedImage> buildClosestPages(final List<NearbyAircraft> aircraft,
                                                  final AircraftDisplayUnits units) {
        if (aircraft.isEmpty()) {
            return List.of(noAircraftPage());
        }

        final NearbyAircraft closest = aircraft.getFirst();
        final AircraftEnrichment enrichment = enrichClosest(aircraft);

        final var pages = new java.util.ArrayList<BufferedImage>(3);
        pages.add(closestIdentityPage(closest, units));
        if (enrichment != null && enrichment.hasRegistry()) {
            pages.add(registryPage(enrichment));
        }
        if (enrichment != null && enrichment.hasRoute()) {
            pages.add(routePage(enrichment));
        }
        return pages;
    }

    private BufferedImage noAircraftPage() {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawTextAlignCenter(image, ledBoardFont8Px, "NO ACFT", 15, Color.RED);
        paintToolsService.drawTextAlignCenter(image, cgPixel5Px, "IN RANGE", 26, Color.RED);
        return image;
    }

    /** Page 1: identity + telemetry. */
    private BufferedImage closestIdentityPage(final NearbyAircraft a, final AircraftDisplayUnits units) {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawText(image, ledBoardFont8Px, truncate(a.callsign(), 7), 0, 8, Color.WHITE);
        paintToolsService.drawText(image, cgPixel5Px, closestTypeLine(a), 0, 15, Color.CYAN);
        paintToolsService.drawText(image, cgPixel5Px, closestFlightLine(a, units), 0, 22, Color.GREEN);
        paintToolsService.drawText(image, cgPixel5Px, closestDistanceLine(a, units), 0, 29, altitudeColor(a));
        return image;
    }

    /** Page 2: registry — owner, country, manufacturer, full type. */
    private BufferedImage registryPage(final AircraftEnrichment enrichment) {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.owner()), 12), 0, 6, Color.CYAN);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.ownerCountry()), 12), 0, 13, Color.WHITE);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.manufacturer()), 12), 0, 20, Color.YELLOW);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.displayType(12)), 12), 0, 27, Color.GREEN);
        return image;
    }

    /** Page 3: route — airport codes, cities, airline. */
    private BufferedImage routePage(final AircraftEnrichment enrichment) {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawText(image, cgPixel5Px, truncate(enrichment.originCode() + ">" + enrichment.destinationCode(), 12), 0, 6, Color.GREEN);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.originCity()), 12), 0, 13, Color.WHITE);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.destinationCity()), 12), 0, 20, Color.WHITE);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.airlineName()), 12), 0, 27, Color.YELLOW);
        return image;
    }

    /**
     * Enrichment legs are short blocking HTTP lookups — one virtual thread per leg,
     * no pooling or lifecycle to manage.
     */
    private static final ExecutorService ENRICHMENT_EXECUTOR = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("aircraft-enrich-", 0).factory());

    /**
     * Enrichment for the closest aircraft; null when nothing could be resolved. The
     * hex-details and callsign-route lookups run concurrently; a failing leg degrades
     * to {@link Optional#empty()} instead of failing the render.
     */
    private AircraftEnrichment enrichClosest(final List<NearbyAircraft> aircraft) {
        if (aircraft.isEmpty()) {
            return null;
        }
        return enrichAircraft(aircraft.getFirst());
    }

    private CompletableFuture<Optional<AircraftEnrichment>> enrichAircraftAsync(final NearbyAircraft aircraft) {
        // A failing leg degrades to Optional.empty() HERE (not only at the outer join)
        // so the merge still runs and the positional-feed synthesis can fill the gap.
        final CompletableFuture<Optional<AdsbdbAircraftData>> details = CompletableFuture.supplyAsync(
                () -> aircraftInfoClient.getAircraftDetails(aircraft.hex()), ENRICHMENT_EXECUTOR)
                .exceptionally(e -> {
                    log.warn("aircraft details leg failed: {}", rootToString(e));
                    return Optional.empty();
                });
        final CompletableFuture<Optional<AdsbdbRouteData>> route = CompletableFuture.supplyAsync(
                () -> aircraftInfoClient.getRoute(aircraft.callsign()), ENRICHMENT_EXECUTOR)
                .exceptionally(e -> {
                    log.warn("aircraft route leg failed: {}", rootToString(e));
                    return Optional.empty();
                });
        // of(null, null, …) is null by contract; thenCombine must never see a null result.
        return details.thenCombine(route, (d, r) ->
                Optional.ofNullable(AircraftEnrichment.of(d.orElse(null), r.orElse(null), aircraft)));
    }

    private AircraftEnrichment enrichAircraft(final NearbyAircraft aircraft) {
        return join(enrichAircraftAsync(aircraft)).orElse(null);
    }

    private static <T> Optional<T> join(final CompletableFuture<Optional<T>> leg) {
        try {
            return leg.join();
        } catch (final CompletionException e) {
            log.warn("aircraft enrichment leg failed: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
            return Optional.empty();
        }
    }

    private static String rootToString(final Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.toString();
    }

    private static String closestTypeLine(final NearbyAircraft a) {
        return truncate(fullTypeLine(a), 12);
    }

    private static String fullTypeLine(final NearbyAircraft a) {
        final String type = a.type() != null ? a.type() : "?";
        final String reg = a.registration() != null ? a.registration() : "";
        return (type + " " + reg).trim();
    }

    private static String closestFlightLine(final NearbyAircraft a, final AircraftDisplayUnits units) {
        return formatAltitude(a, units) + " " + formatSpeed(a, units);
    }

    private static String closestDistanceLine(final NearbyAircraft a, final AircraftDisplayUnits units) {
        final String distance = formatDistance(a, units);
        final String trend = verticalTrend(a);
        return trend.isEmpty() ? distance : distance + " " + trend;
    }

    /* --------------------------------------------------------------------
     * LIST
     * ------------------------------------------------------------------*/

    private BufferedImage renderList(final List<NearbyAircraft> aircraft, final AircraftDisplayUnits units) {
        final BufferedImage image = paintToolsService.newImage();

        if (aircraft.isEmpty()) {
            paintToolsService.drawTextAlignCenter(image, ledBoardFont8Px, "NO ACFT", 15, Color.RED);
            paintToolsService.drawTextAlignCenter(image, cgPixel5Px, "IN RANGE", 26, Color.RED);
            return image;
        }

        final List<NearbyAircraft> top = aircraft.subList(0, Math.min(aircraft.size(), 5));
        int y = 5;
        for (final NearbyAircraft a : top) {
            paintToolsService.drawText(image, cgPixel5Px, truncate(a.callsign(), 7), 0, y, altitudeColor(a));
            paintToolsService.drawTextAlignRight(image, cgPixel5Px, formatDistance(a, units), y, Color.WHITE);
            y += 6;
        }
        return image;
    }

    /* --------------------------------------------------------------------
     * ACMD command path (plan §6): the same data fetch as the frame path,
     * rendering swapped to ACMD primitives. Every batch starts with CLS
     * black, embeds its FONT pages, and — for RADAR — ends with one SWEEP
     * the panel ticks locally: zero per-frame MQTT traffic.
     * ------------------------------------------------------------------ */

    @Override
    public byte[] renderCommandBatch(final AircraftScreenConfig screenConfig) {
        final List<NearbyAircraft> aircraft = getAircraft(screenConfig);
        final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
        switch (screenConfig.displayMode()) {
            case LIST -> listCommands(batch, aircraft, screenConfig.units());
            case CLOSEST -> closestCommands(batch, aircraft, screenConfig);
            case RADAR -> radarCommands(batch, aircraft, closestOrNull(aircraft),
                    enrichClosest(aircraft), RadarInfoPage.TELEMETRY, screenConfig, SWEEP_DEG_PER_SEC);
        }
        return batch.build();
    }

    /**
     * CLOSEST cycles its info pages as separate batches (the frame path's
     * {@code closestStream} semantics: identity always, registry/route when enrichment
     * resolves them, one pass through the pages with the same dwell math). LIST/RADAR
     * and the no-aircraft case stay single-batch via the interface default.
     */
    @Override
    public BatchStream renderCommandBatches(final AircraftScreenConfig screenConfig) {
        if (screenConfig.displayMode() != AircraftDisplayMode.CLOSEST) {
            return CommandScreenService.super.renderCommandBatches(screenConfig);
        }

        final List<NearbyAircraft> aircraft = getAircraft(screenConfig);
        if (aircraft.isEmpty()) {
            final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
            noAircraftCommands(batch);
            return new BatchStream(List.of(batch.build()), 0);
        }

        final AircraftEnrichment enrichment = enrichClosest(aircraft);
        final var batches = new java.util.ArrayList<byte[]>(3);

        final CommandBatch identity = CommandBatch.builder().cls(AcmdMirror.BLACK);
        closestCommands(identity, aircraft, screenConfig);
        batches.add(identity.build());
        if (enrichment != null && enrichment.hasRegistry()) {
            final CommandBatch registry = CommandBatch.builder().cls(AcmdMirror.BLACK);
            registryCommands(registry, enrichment);
            batches.add(registry.build());
        }
        if (enrichment != null && enrichment.hasRoute()) {
            final CommandBatch route = CommandBatch.builder().cls(AcmdMirror.BLACK);
            routeCommands(route, enrichment);
            batches.add(route.build());
        }
        if (batches.size() == 1) {
            return new BatchStream(batches, 0); // identity only: nothing to cycle
        }
        // Same dwell math as closestStream (page count >= 2 here, so the frame path's
        // protocol-minimum padding never applies).
        final long slotMillis = screenConfig.durationSeconds() * 1000L;
        final long dwellMs = Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS,
                Math.min(65_535, slotMillis / batches.size()));
        return new BatchStream(batches, dwellMs);
    }

    /**
     * RADAR refreshes live: each refresh render re-fetches the aircraft list (the
     * adsb.lol stale-while-revalidate cache serves reads instantly and re-fetches in
     * the background — one supplier call is one cache read, never a blocking fetch)
     * and re-renders blips + info column. The tracked aircraft stays the closest one
     * (selection box), while the info column cycles its detail pages in equal slices
     * of the display time: the refresh cadence — and with it the sweep speed, one
     * whole revolution per refresh — is derived from the slot length so every page
     * swap lands on a refresh grid point (a revolution boundary) and the rotation's
     * last page is never truncated by the slot end. LIST/CLOSEST data moves too
     * slowly within a slot to be worth the republish traffic: they keep the batch path.
     */
    @Override
    public RefreshStream renderCommandRefresh(final AircraftScreenConfig screenConfig) {
        if (screenConfig.displayMode() != AircraftDisplayMode.RADAR) {
            return null;
        }
        final long slotStartNanos = System.nanoTime();
        final long slotMillis = screenConfig.durationSeconds() * 1000L;
        final List<NearbyAircraft> aircraft = getAircraft(screenConfig);
        final AircraftEnrichment enrichment = enrichClosest(aircraft);
        final int pageCount = radarInfoPages(enrichment).size();
        final long refreshMs = radarRefreshMs(slotMillis, pageCount);
        final int refreshesPerPage = radarRefreshesPerPage(slotMillis, pageCount, refreshMs);
        final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
        radarCommands(batch, aircraft, closestOrNull(aircraft), enrichment, RadarInfoPage.TELEMETRY,
                screenConfig, radarSweepDegPerSec(refreshMs));
        return new RefreshStream(batch.build(),
                () -> renderRadarPageBatch(screenConfig, slotStartNanos, refreshMs, refreshesPerPage),
                refreshMs);
    }

    private byte[] renderRadarPageBatch(final AircraftScreenConfig screenConfig, final long slotStartNanos,
                                        final long refreshMs, final int refreshesPerPage) {
        final List<NearbyAircraft> aircraft = getAircraft(screenConfig);
        final AircraftEnrichment enrichment = enrichClosest(aircraft);
        final List<RadarInfoPage> pages = radarInfoPages(enrichment);
        final RadarInfoPage page = radarPageForRenderElapsed(
                elapsedMs(slotStartNanos), pages, refreshMs, refreshesPerPage);
        final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
        radarCommands(batch, aircraft, closestOrNull(aircraft), enrichment, page,
                screenConfig, radarSweepDegPerSec(refreshMs));
        return batch.build();
    }

    /**
     * The info page a refresh batch rendered {@code renderElapsedMs} into the slot must
     * carry: the job renders pipelined — each next batch is produced during the
     * interval BEFORE its publish grid point — so the page is selected for the target
     * time {@code renderElapsedMs + refreshMs}, not for the render time.
     * Without the lead every page boundary trails the grid by one refresh interval,
     * and a rotation's last page gets squeezed to the slot's final interval (or
     * pushed past the slot transition entirely).
     */
    static RadarInfoPage radarPageForRenderElapsed(final long renderElapsedMs, final List<RadarInfoPage> pages,
                                                   final long refreshMs, final int refreshesPerPage) {
        return pages.get(radarPageIndex(renderElapsedMs + refreshMs, refreshMs,
                refreshesPerPage, pages.size()));
    }

    private static long elapsedMs(final long slotStartNanos) {
        return (System.nanoTime() - slotStartNanos) / 1_000_000;
    }

    /** LIST: one TEXT row per aircraft — callsign left in its altitude color, distance right-aligned. */
    private void listCommands(final CommandBatch batch, final List<NearbyAircraft> aircraft,
                              final AircraftDisplayUnits units) {
        if (aircraft.isEmpty()) {
            noAircraftCommands(batch);
            return;
        }
        final FontPageExtractor.FontPage page = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, page.glyphs());
        final List<NearbyAircraft> top = aircraft.subList(0, Math.min(aircraft.size(), 5));
        int baselineY = 5;
        for (final NearbyAircraft a : top) {
            textLine(batch, page, PAGE_CGPIXEL_ID, truncate(a.callsign(), 7), 0, baselineY, altitudeColor(a));
            final String distance = formatDistance(a, units);
            textLine(batch, page, PAGE_CGPIXEL_ID, distance,
                    AcmdMirror.WIDTH - page.width(distance), baselineY, Color.WHITE);
            baselineY += 6;
        }
    }

    /**
     * CLOSEST: the identity page (callsign, type, telemetry, distance) — the one page
     * every frame-stream cycle shows. Registry/route pages are frame-path only: one
     * ACMD batch renders one static page. Lines that would overflow the canvas scroll
     * their full text instead of being truncated.
     */
    private void closestCommands(final CommandBatch batch, final List<NearbyAircraft> aircraft,
                                 final AircraftScreenConfig screenConfig) {
        if (aircraft.isEmpty()) {
            noAircraftCommands(batch);
            return;
        }
        final NearbyAircraft closest = aircraft.getFirst();
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());

        textLine(batch, ledPage, PAGE_LEDBOARD_ID, truncate(closest.callsign(), 7), 0, 8, Color.WHITE);
        textOrScroll(batch, cgPage, PAGE_CGPIXEL_ID, fullTypeLine(closest), 15, Color.CYAN);
        final String flightLine = closestFlightLine(closest, screenConfig.units());
        textOrScroll(batch, cgPage, PAGE_CGPIXEL_ID, flightLine, 22, Color.GREEN);
        textOrScroll(batch, cgPage, PAGE_CGPIXEL_ID, closestDistanceLine(closest, screenConfig.units()), 29, altitudeColor(closest));
    }

    /**
     * Registry page (the frame path's registryPage as commands). Lines are
     * fit-truncated to the canvas instead of scrolled: these pages hold up to four
     * lines and a batch runs at most one parametric — a second SCROLL would render
     * nothing at all, so the frame path's truncation look is the safe equivalent.
     */
    private void registryCommands(final CommandBatch batch, final AircraftEnrichment enrichment) {
        final FontPageExtractor.FontPage page = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, page.glyphs());
        fitTextLine(batch, page, orDash(enrichment.owner()), 6, Color.CYAN);
        fitTextLine(batch, page, orDash(enrichment.ownerCountry()), 13, Color.WHITE);
        fitTextLine(batch, page, orDash(enrichment.manufacturer()), 20, Color.YELLOW);
        fitTextLine(batch, page, orDash(enrichment.displayType(12)), 27, Color.GREEN);
    }

    /** Route page (the frame path's routePage as commands); same fit-truncation rule. */
    private void routeCommands(final CommandBatch batch, final AircraftEnrichment enrichment) {
        final FontPageExtractor.FontPage page = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, page.glyphs());
        fitTextLine(batch, page, enrichment.originCode() + ">" + enrichment.destinationCode(), 6, Color.GREEN);
        fitTextLine(batch, page, orDash(enrichment.originCity()), 13, Color.WHITE);
        fitTextLine(batch, page, orDash(enrichment.destinationCity()), 20, Color.WHITE);
        fitTextLine(batch, page, orDash(enrichment.airlineName()), 27, Color.YELLOW);
    }

    /** TEXT at the frame path's baseline, truncated to the frame path's 12 chars and to the canvas width. */
    private static void fitTextLine(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                    final String text, final int baselineY, final Color color) {
        textLine(batch, page, PAGE_CGPIXEL_ID, fitTruncate(page, text), 0, baselineY, color);
    }

    private static String fitTruncate(final FontPageExtractor.FontPage page, final String text) {
        String fitted = truncate(text, 12);
        while (page.width(fitted) > AcmdMirror.WIDTH && fitted.length() > 1) {
            fitted = fitted.substring(0, fitted.length() - 1);
        }
        return fitted;
    }

    /**
     * RADAR: CIRC rings + FILL blips (the frame path's projection) + the tracked
     * aircraft's selection box + info-column TEXT + one SWEEP. {@code selected} is the
     * closest aircraft (null = empty sky); the info column renders the given
     * {@link RadarInfoPage} — the refresh stream rotates pages, a single batch cannot.
     */
    private void radarCommands(final CommandBatch batch, final List<NearbyAircraft> aircraft,
                               final NearbyAircraft selected, final AircraftEnrichment enrichment,
                               final RadarInfoPage infoPage, final AircraftScreenConfig screenConfig,
                               final int sweepDegPerSec) {
        // Scope rings in the frame path's draw order (inner, outer, innermost).
        batch.circ(SCOPE_CX, SCOPE_CY, SCOPE_RING_MID_RADIUS, Rgb565.of(SCOPE_RING));
        batch.circ(SCOPE_CX, SCOPE_CY, SCOPE_RADIUS, Rgb565.of(SCOPE_GREEN));
        batch.circ(SCOPE_CX, SCOPE_CY, SCOPE_RING_INNER_RADIUS, Rgb565.of(SCOPE_RING));
        batch.pix(SCOPE_CX, SCOPE_CY, Rgb565.of(SCOPE_GREEN));

        // Static blips at the frame path's polar projection; fully lit (the command path
        // has no sweep-phase decay — the SWEEP ticks over the static base).
        final int radiusNm = Math.max(1, screenConfig.radiusNm());
        for (final NearbyAircraft a : aircraft) {
            final double r = Math.min(1.0, a.distanceNm() / radiusNm) * SCOPE_RADIUS;
            final double rad = Math.toRadians(a.bearingDeg());
            final int x = SCOPE_CX + (int) Math.round(r * Math.sin(rad));
            final int y = SCOPE_CY - (int) Math.round(r * Math.cos(rad));
            batch.fill(Math.max(1, x - 1), Math.max(1, y - 1), 2, 2, Rgb565.of(altitudeColor(a)));
        }

        // The tracked blip (the one the info column describes) gets the frame path's
        // white 4×4 selection box — never sweep-dimmed there, so identical here.
        if (selected != null) {
            final double r = Math.min(1.0, selected.distanceNm() / radiusNm) * SCOPE_RADIUS;
            final double rad = Math.toRadians(selected.bearingDeg());
            final int x = SCOPE_CX + (int) Math.round(r * Math.sin(rad));
            final int y = SCOPE_CY - (int) Math.round(r * Math.cos(rad));
            batch.rect(Math.max(0, x - 2), Math.max(0, y - 2), 4, 4, Rgb565.of(Color.WHITE));
        }

        // Info column (the frame path's drawInfoColumn pages as TEXT).
        final FontPageExtractor.FontPage page = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, page.glyphs());
        if (selected == null) {
            textLine(batch, page, PAGE_CGPIXEL_ID, "NO", INFO_X, 11, Color.RED);
            textLine(batch, page, PAGE_CGPIXEL_ID, "ACFT", INFO_X, 19, Color.RED);
        } else {
            textLine(batch, page, PAGE_CGPIXEL_ID, truncate(selected.callsign(), 6), INFO_X, 5, Color.WHITE);
            switch (infoPage) {
                case ROUTE -> {
                    if (enrichment != null && enrichment.hasRoute()) {
                        textLine(batch, page, PAGE_CGPIXEL_ID, truncate(enrichment.originCode(), 5), INFO_X, 12, Color.GREEN);
                        textLine(batch, page, PAGE_CGPIXEL_ID, truncate(enrichment.destinationCode(), 5), INFO_X, 19, Color.CYAN);
                    } else {
                        textLine(batch, page, PAGE_CGPIXEL_ID, "ROUTE", INFO_X, 12, SCOPE_GREEN);
                        textLine(batch, page, PAGE_CGPIXEL_ID, "UNK", INFO_X, 19, SCOPE_GREEN);
                    }
                    textLine(batch, page, PAGE_CGPIXEL_ID,
                            truncate(orDash(enrichment == null ? null : enrichment.owner()), 6), INFO_X, 26, Color.YELLOW);
                }
                case REGISTRY -> {
                    textLine(batch, page, PAGE_CGPIXEL_ID,
                            truncate(orDash(enrichment == null ? null : enrichment.ownerCountry()), 6), INFO_X, 12, Color.CYAN);
                    textLine(batch, page, PAGE_CGPIXEL_ID,
                            truncate(orDash(enrichment == null ? null : enrichment.manufacturer()), 6), INFO_X, 19, Color.WHITE);
                    textLine(batch, page, PAGE_CGPIXEL_ID,
                            truncate(orDash(enrichment == null ? null : enrichment.displayType(6)), 6), INFO_X, 26, Color.GREEN);
                }
                case TELEMETRY -> {
                    textLine(batch, page, PAGE_CGPIXEL_ID, formatDistance(selected, screenConfig.units()), INFO_X, 12, altitudeColor(selected));
                    textLine(batch, page, PAGE_CGPIXEL_ID, formatAltitude(selected, screenConfig.units()), INFO_X, 19, Color.CYAN);
                    textLine(batch, page, PAGE_CGPIXEL_ID, formatSpeed(selected, screenConfig.units()), INFO_X, 26, Color.YELLOW);
                }
            }
        }

        // The single parametric primitive (first wins): one whole revolution per refresh
        // — the speed derived from the slot's page-tiled cadence on the live path, the
        // plain one-per-RADAR_REFRESH_MS default on the single-batch fallback.
        batch.sweep(SCOPE_CX, SCOPE_CY, SCOPE_RADIUS, Rgb565.of(SWEEP_LEAD), sweepDegPerSec);
    }

    private void noAircraftCommands(final CommandBatch batch) {
        final FontPageExtractor.FontPage ledPage = ledBoardPage();
        final FontPageExtractor.FontPage cgPage = cgPixelPage();
        batch.fontPage(PAGE_LEDBOARD_ID, ledPage.glyphs())
                .fontPage(PAGE_CGPIXEL_ID, cgPage.glyphs());
        centerText(batch, ledPage, PAGE_LEDBOARD_ID, "NO ACFT", 15, Color.RED);
        centerText(batch, cgPage, PAGE_CGPIXEL_ID, "IN RANGE", 26, Color.RED);
    }

    /** TEXT at the frame path's baseline (ACMD y = glyph line-box top = baseline + lineTop). */
    private static void textLine(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                 final int pageId, final String text, final int x,
                                 final int baselineY, final Color color) {
        batch.text(pageId, x, baselineY + page.lineTop(), Rgb565.of(color), ascii(text));
    }

    private static void centerText(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                   final int pageId, final String text, final int baselineY, final Color color) {
        final String sanitized = ascii(text);
        textLine(batch, page, pageId, sanitized, AcmdMirror.WIDTH / 2 - page.width(sanitized) / 2, baselineY, color);
    }

    /**
     * ACMD TEXT/SCROLL payloads are ASCII 32..126 by spec, but adsbdb enrichment is
     * free-text (city/owner/manufacturer names like "Bogotá", "São Paulo"): one
     * accented character would fail the whole batch build ({@code requireAscii}) and
     * drop the panel to the frame path. Transliterate instead — NFD strips the
     * diacritics, any surviving non-ASCII code point (non-Latin scripts, symbols)
     * becomes {@code ?}, which the firmware renders as an unknown-glyph 4 px skip.
     * Sanitize before width math so layout decisions (fit/scroll/center) match what
     * is actually drawn. Fast path: pure-ASCII input returns unchanged.
     */
    private static String ascii(final String s) {
        if (s.chars().allMatch(c -> c >= 32 && c <= 126)) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length());
        Normalizer.normalize(s, Normalizer.Form.NFD).codePoints().forEach(cp -> {
            if (cp >= 32 && cp <= 126) {
                sb.append((char) cp);
            } else if (Character.getType(cp) != Character.NON_SPACING_MARK) {
                sb.append('?');
            }
        });
        return sb.toString();
    }

    /**
     * TEXT when the full string fits the canvas (then it is also what the frame path
     * shows — its fixed truncation lengths never exceed the canvas); a string that
     * overflows becomes one SCROLL of the whole, untruncated text across the line
     * instead (the frame path would simply cut it off).
     */
    private static void textOrScroll(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                     final int pageId, final String fullText,
                                     final int baselineY, final Color color) {
        final String sanitized = ascii(fullText);
        if (page.width(sanitized) <= AcmdMirror.WIDTH) {
            textLine(batch, page, pageId, sanitized, 0, baselineY, color);
            return;
        }
        batch.scroll(0, baselineY + page.lineTop(), AcmdMirror.WIDTH, SCROLL_REGION_HEIGHT,
                pageId, Rgb565.of(color), SCROLL_MS_PER_PX, sanitized);
    }

    private FontPageExtractor.FontPage ledBoardPage() {
        FontPageExtractor.FontPage page = ledBoardPage;
        if (page == null) {
            page = FontPageExtractor.extract(ledBoardFont8Px);
            ledBoardPage = page;
        }
        return page;
    }

    private FontPageExtractor.FontPage cgPixelPage() {
        FontPageExtractor.FontPage page = cgPixelPage;
        if (page == null) {
            page = FontPageExtractor.extract(cgPixel5Px);
            cgPixelPage = page;
        }
        return page;
    }

    /* --------------------------------------------------------------------
     * Formatting helpers
     * ------------------------------------------------------------------*/

    private static final double FT_TO_M = 0.3048;
    private static final double KT_TO_KMH = 1.852;
    private static final double NM_TO_KM = 1.852;

    private static String formatDistance(final NearbyAircraft a, final AircraftDisplayUnits units) {
        if (units == AircraftDisplayUnits.METRIC) {
            return Math.round(a.distanceNm() * NM_TO_KM) + "KM";
        }
        return Math.round(a.distanceNm()) + "NM";
    }

    private static String formatAltitude(final NearbyAircraft a, final AircraftDisplayUnits units) {
        if (a.onGround()) {
            return "GND";
        }
        if (a.altitudeFt() == null) {
            return "-----";
        }
        if (units == AircraftDisplayUnits.METRIC) {
            return Math.max(0, Math.round(a.altitudeFt() * FT_TO_M)) + "M";
        }
        return "FL" + Math.max(0, a.altitudeFt() / 100);
    }

    private static String formatSpeed(final NearbyAircraft a, final AircraftDisplayUnits units) {
        if (a.groundSpeedKt() == null) {
            return units == AircraftDisplayUnits.METRIC ? "---KMH" : "---KT";
        }
        final double value = units == AircraftDisplayUnits.METRIC
                ? a.groundSpeedKt() * KT_TO_KMH
                : a.groundSpeedKt();
        return Math.round(value) + (units == AircraftDisplayUnits.METRIC ? "KMH" : "KT");
    }

    private static String verticalTrend(final NearbyAircraft a) {
        if (a.verticalRateFpm() == null) {
            return "";
        }
        if (a.verticalRateFpm() > 300) {
            return "CLB";
        }
        if (a.verticalRateFpm() < -300) {
            return "DES";
        }
        return "";
    }

    /** Blip/row color by altitude band; ground traffic is yellow. */
    private static Color altitudeColor(final NearbyAircraft a) {
        if (a.onGround()) {
            return Color.YELLOW;
        }
        final int ft = a.altitudeFt() != null ? a.altitudeFt() : 0;
        if (ft < 10_000) {
            return Color.GREEN;
        }
        if (ft < 20_000) {
            return Color.CYAN;
        }
        if (ft < 30_000) {
            return Color.MAGENTA;
        }
        return new Color(255, 60, 60);
    }

    private static String truncate(final String s, final int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String orDash(final String s) {
        return s == null || s.isBlank() ? "-" : s;
    }
}
