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
     * Info-page dwell for the radar column's alternating route page. The loop duration
     * is an exact multiple of this, so the alternation flips at the loop's frame
     * midpoint — a dwell of exactly {@code PAGE_DWELL_MS} whenever the frame delay
     * divides it evenly (default 100 ms → 40 frames per page).
     */
    static final long PAGE_DWELL_MS = 4000;

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

    /** ACMD SWEEP speed: one revolution per SWEEP_REVOLUTION_MS (4000 ms → 90°/s). */
    static final int SWEEP_DEG_PER_SEC = (int) Math.round(360_000.0 / SWEEP_REVOLUTION_MS);

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
            case LIST -> renderList(aircraft);
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
     * Route/telemetry page flip at the loop's frame midpoint: exactly one alternation
     * per half loop, phase-stable across the modulo loop boundary by construction
     * (equals a {@link #PAGE_DWELL_MS} wall-clock dwell when the delay divides it evenly).
     */
    static boolean radarRoutePage(final int frameIndex, final int frameCount) {
        return (2 * frameIndex / frameCount) % 2 == 1;
    }

    private List<BufferedImage> radarFrames(final List<NearbyAircraft> aircraft,
                                            final AircraftScreenConfig screenConfig) {
        final int frameDelayMs = Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS, screenConfig.frameDelayMs());
        final int frameCount = radarFrameCount(frameDelayMs);

        final AircraftEnrichment enrichment = enrichClosest(aircraft);
        final boolean hasRoutePage = enrichment != null && (enrichment.hasRoute() || enrichment.owner() != null);

        final var frames = new java.util.ArrayList<BufferedImage>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            final boolean routePage = hasRoutePage && radarRoutePage(i, frameCount);
            frames.add(renderRadarFrame(aircraft, radarSweepDeg(i, frameCount), screenConfig, enrichment, routePage));
        }
        return frames;
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
                                           final double sweepDeg,
                                           final AircraftScreenConfig screenConfig,
                                           final AircraftEnrichment enrichment,
                                           final boolean routePage) {
        final BufferedImage image = paintToolsService.newImage();
        drawScope(image);
        drawBlips(image, aircraft, sweepDeg, screenConfig);
        drawSweep(image, sweepDeg);
        drawInfoColumn(image, aircraft, screenConfig.units(), enrichment, routePage);
        return image;
    }

    private BufferedImage renderRadarStatic(final List<NearbyAircraft> aircraft,
                                            final AircraftScreenConfig screenConfig) {
        return renderRadarFrame(aircraft, 0, screenConfig, null, false);
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

    private void drawInfoColumn(final BufferedImage image, final List<NearbyAircraft> aircraft,
                                final AircraftDisplayUnits units,
                                final AircraftEnrichment enrichment,
                                final boolean routePage) {
        if (aircraft.isEmpty()) {
            paintToolsService.drawText(image, cgPixel5Px, "NO", INFO_X, 11, Color.RED);
            paintToolsService.drawText(image, cgPixel5Px, "ACFT", INFO_X, 19, Color.RED);
            return;
        }

        final NearbyAircraft closest = aircraft.getFirst();
        paintToolsService.drawText(image, cgPixel5Px, truncate(closest.callsign(), 6), INFO_X, 5, Color.WHITE);

        if (routePage && enrichment != null) {
            if (enrichment.hasRoute()) {
                paintToolsService.drawText(image, cgPixel5Px, truncate(enrichment.originCode(), 5), INFO_X, 12, Color.GREEN);
                paintToolsService.drawText(image, cgPixel5Px, truncate(enrichment.destinationCode(), 5), INFO_X, 19, Color.CYAN);
            } else {
                paintToolsService.drawText(image, cgPixel5Px, "ROUTE", INFO_X, 12, SCOPE_GREEN);
                paintToolsService.drawText(image, cgPixel5Px, "UNK", INFO_X, 19, SCOPE_GREEN);
            }
            paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.owner()), 6), INFO_X, 26, Color.YELLOW);
            return;
        }

        paintToolsService.drawText(image, cgPixel5Px, Math.round(closest.distanceNm()) + "NM", INFO_X, 12, altitudeColor(closest));
        paintToolsService.drawText(image, cgPixel5Px, formatAltitude(closest, units), INFO_X, 19, Color.CYAN);
        paintToolsService.drawText(image, cgPixel5Px, formatSpeed(closest, units), INFO_X, 26, Color.YELLOW);
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
        paintToolsService.drawText(image, cgPixel5Px, closestDistanceLine(a), 0, 29, altitudeColor(a));
        return image;
    }

    /** Page 2: registry — owner, country, manufacturer, full type. */
    private BufferedImage registryPage(final AircraftEnrichment enrichment) {
        final BufferedImage image = paintToolsService.newImage();
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.owner()), 12), 0, 6, Color.CYAN);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.ownerCountry()), 12), 0, 13, Color.WHITE);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.manufacturer()), 12), 0, 20, Color.YELLOW);
        paintToolsService.drawText(image, cgPixel5Px, truncate(orDash(enrichment.typeName()), 12), 0, 27, Color.GREEN);
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
        final NearbyAircraft closest = aircraft.getFirst();
        final CompletableFuture<Optional<AdsbdbAircraftData>> details = CompletableFuture.supplyAsync(
                () -> aircraftInfoClient.getAircraftDetails(closest.hex()), ENRICHMENT_EXECUTOR);
        final CompletableFuture<Optional<AdsbdbRouteData>> route = CompletableFuture.supplyAsync(
                () -> aircraftInfoClient.getRoute(closest.callsign()), ENRICHMENT_EXECUTOR);
        return AircraftEnrichment.of(join(details).orElse(null), join(route).orElse(null));
    }

    private static <T> Optional<T> join(final CompletableFuture<Optional<T>> leg) {
        try {
            return leg.join();
        } catch (final CompletionException e) {
            log.warn("aircraft enrichment leg failed: {}", e.getCause() != null ? e.getCause().toString() : e.toString());
            return Optional.empty();
        }
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

    private static String closestDistanceLine(final NearbyAircraft a) {
        final String distance = Math.round(a.distanceNm()) + "NM";
        final String trend = verticalTrend(a);
        return trend.isEmpty() ? distance : distance + " " + trend;
    }

    /* --------------------------------------------------------------------
     * LIST
     * ------------------------------------------------------------------*/

    private BufferedImage renderList(final List<NearbyAircraft> aircraft) {
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
            paintToolsService.drawTextAlignRight(image, cgPixel5Px, Math.round(a.distanceNm()) + "NM", y, Color.WHITE);
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
            case LIST -> listCommands(batch, aircraft);
            case CLOSEST -> closestCommands(batch, aircraft, screenConfig);
            case RADAR -> radarCommands(batch, aircraft, screenConfig);
        }
        return batch.build();
    }

    /** LIST: one TEXT row per aircraft — callsign left in its altitude color, distance right-aligned. */
    private void listCommands(final CommandBatch batch, final List<NearbyAircraft> aircraft) {
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
            final String distance = Math.round(a.distanceNm()) + "NM";
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
        textOrScroll(batch, cgPage, PAGE_CGPIXEL_ID, closestDistanceLine(closest), 29, altitudeColor(closest));
    }

    /** RADAR: CIRC rings + FILL blips (the frame path's projection) + info-column TEXT + one SWEEP. */
    private void radarCommands(final CommandBatch batch, final List<NearbyAircraft> aircraft,
                               final AircraftScreenConfig screenConfig) {
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

        // Info column, telemetry page (the alternating route page is frame-path only:
        // the base canvas is static and the SWEEP is the batch's one parametric).
        final FontPageExtractor.FontPage page = cgPixelPage();
        batch.fontPage(PAGE_CGPIXEL_ID, page.glyphs());
        if (aircraft.isEmpty()) {
            textLine(batch, page, PAGE_CGPIXEL_ID, "NO", INFO_X, 11, Color.RED);
            textLine(batch, page, PAGE_CGPIXEL_ID, "ACFT", INFO_X, 19, Color.RED);
        } else {
            final NearbyAircraft closest = aircraft.getFirst();
            textLine(batch, page, PAGE_CGPIXEL_ID, truncate(closest.callsign(), 6), INFO_X, 5, Color.WHITE);
            textLine(batch, page, PAGE_CGPIXEL_ID, Math.round(closest.distanceNm()) + "NM", INFO_X, 12, altitudeColor(closest));
            textLine(batch, page, PAGE_CGPIXEL_ID, formatAltitude(closest, screenConfig.units()), INFO_X, 19, Color.CYAN);
            textLine(batch, page, PAGE_CGPIXEL_ID, formatSpeed(closest, screenConfig.units()), INFO_X, 26, Color.YELLOW);
        }

        // The single parametric primitive (first wins): one revolution per SWEEP_REVOLUTION_MS.
        batch.sweep(SCOPE_CX, SCOPE_CY, SCOPE_RADIUS, Rgb565.of(SWEEP_LEAD), SWEEP_DEG_PER_SEC);
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
        batch.text(pageId, x, baselineY + page.lineTop(), Rgb565.of(color), text);
    }

    private static void centerText(final CommandBatch batch, final FontPageExtractor.FontPage page,
                                   final int pageId, final String text, final int baselineY, final Color color) {
        textLine(batch, page, pageId, text, AcmdMirror.WIDTH / 2 - page.width(text) / 2, baselineY, color);
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
        if (page.width(fullText) <= AcmdMirror.WIDTH) {
            textLine(batch, page, pageId, fullText, 0, baselineY, color);
            return;
        }
        batch.scroll(0, baselineY + page.lineTop(), AcmdMirror.WIDTH, SCROLL_REGION_HEIGHT,
                pageId, Rgb565.of(color), SCROLL_MS_PER_PX, fullText);
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
