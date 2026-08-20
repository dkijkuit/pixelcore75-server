package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayMode;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AircraftScreenConfig.AircraftDisplayUnits;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
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

@Slf4j
@Service
@RequiredArgsConstructor
public class AircraftScreenService implements FrameScreenService<AircraftScreenConfig> {

    public static final int MIN_RADIUS_NM = 5;
    public static final int MAX_RADIUS_NM = 250;
    public static final int DEFAULT_FRAME_DELAY_MS = 100;

    /**
     * Target sweep revolution period, independent of the slot length. Each slot rotates
     * by a whole number of revolutions (see {@link #renderFrames}) starting from a fixed
     * base angle, so it ends exactly where it started: consecutive radar slots chain
     * seamlessly (the sweep pauses for the inline upload, then moves on), and panel-side
     * loop restarts are invisible. A wall-clock phase does NOT work here: playback starts
     * only after the upload completes, at a variable delay from render time.
     */
    static final long SWEEP_REVOLUTION_MS = 4000;

    /** Info page dwell for the radar column's alternating route page. */
    static final long PAGE_DWELL_MS = 4000;

    // Radar scope geometry: 32x32 square in the left half, right half is an info column.
    static final int SCOPE_CX = 16;
    static final int SCOPE_CY = 16;
    static final int SCOPE_RADIUS = 14;
    static final int INFO_X = 34;

    static final Color SCOPE_GREEN = new Color(0, 90, 0);
    static final Color SCOPE_RING = new Color(0, 50, 0);
    static final Color SWEEP_TRAIL = new Color(0, 60, 0);

    final PaintToolsService paintToolsService;
    final Font ledBoardFont8Px;
    final Font cgPixel5Px;
    final AircraftClient aircraftClient;
    final AircraftInfoClient aircraftInfoClient;

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
     * ------------------------------------------------------------------*/

    private List<BufferedImage> radarFrames(final List<NearbyAircraft> aircraft,
                                            final AircraftScreenConfig screenConfig) {
        final long slotMillis = screenConfig.durationSeconds() * 1000L;
        final int frameDelayMs = Math.max(FrameScreenConfig.MIN_FRAME_DELAY_MS, screenConfig.frameDelayMs());
        final int frameCount = (int) Math.max(2, Math.min(200,
                Math.round(slotMillis / (double) frameDelayMs)));

        // Whole revolutions per slot: the sweep ends a slot exactly where it began, so
        // loop restarts and consecutive slots continue the rotation instead of snapping.
        final int revolutions = (int) Math.max(1, Math.ceil(slotMillis / (double) SWEEP_REVOLUTION_MS));
        final double stepDeg = 360.0 * revolutions / frameCount;

        final AircraftEnrichment enrichment = enrichClosest(aircraft);
        final boolean hasRoutePage = enrichment != null && (enrichment.hasRoute() || enrichment.owner() != null);

        final var frames = new java.util.ArrayList<BufferedImage>(frameCount);
        for (int i = 0; i < frameCount; i++) {
            final boolean routePage = hasRoutePage && (i * (long) frameDelayMs / PAGE_DWELL_MS) % 2 == 1;
            frames.add(renderRadarFrame(aircraft, (i + 1) * stepDeg, screenConfig, enrichment, routePage));
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
                new Color(0, 255, 0), new Color(0, 200, 0), new Color(0, 150, 0),
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

    /** Enrichment for the closest aircraft; null when nothing could be resolved. */
    private AircraftEnrichment enrichClosest(final List<NearbyAircraft> aircraft) {
        if (aircraft.isEmpty()) {
            return null;
        }
        final NearbyAircraft closest = aircraft.getFirst();
        return AircraftEnrichment.of(
                aircraftInfoClient.getAircraftDetails(closest.hex()),
                aircraftInfoClient.getRoute(closest.callsign()));
    }

    private static String closestTypeLine(final NearbyAircraft a) {
        final String type = a.type() != null ? a.type() : "?";
        final String reg = a.registration() != null ? a.registration() : "";
        return truncate((type + " " + reg).trim(), 12);
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
