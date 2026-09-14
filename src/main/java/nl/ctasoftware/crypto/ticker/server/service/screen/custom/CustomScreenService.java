package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.LatinFoldService;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.StaticScreenService;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
public class CustomScreenService implements FrameScreenService<CustomScreenConfig>,
        StaticScreenService<CustomScreenConfig>, CommandScreenService<CustomScreenConfig> {

    /** Preview sampling cadence for parametric designs — the SSE command tick, spec §3.6. */
    static final int COMMAND_PREVIEW_TICK_MS = 100;
    static final int COMMAND_PREVIEW_MIN_MS = 2000;
    static final int COMMAND_PREVIEW_MAX_MS = 10000;

    private final PaintToolsService paintToolsService;
    private final Map<PxdFont, Font> fonts;

    /** Extracted ACMD FONT pages per pxd font (deterministic per TTF+size; lazily computed). */
    private final ConcurrentHashMap<PxdFont, FontPageExtractor.FontPage> fontPages = new ConcurrentHashMap<>();

    /** AWT ascents per pxd font, from the same image type the frame path measures on. */
    private final ConcurrentHashMap<PxdFont, Integer> ascents = new ConcurrentHashMap<>();

    public CustomScreenService(final PaintToolsService paintToolsService,
                               final Font cgPixel5Px, final Font miniLineFont8Px, final Font habboFont8Px,
                               final Font ledBoardFont8Px, final Font tinyUnicode8Px, final Font frostFont4Px,
                               final Font grinched7Px) {
        this.paintToolsService = paintToolsService;
        final Map<PxdFont, Font> map = new EnumMap<>(PxdFont.class);
        map.put(PxdFont.CG_PIXEL, cgPixel5Px);
        map.put(PxdFont.MINI_LINE, miniLineFont8Px);
        map.put(PxdFont.HABBO, habboFont8Px);
        map.put(PxdFont.LED_BOARD, ledBoardFont8Px);
        map.put(PxdFont.TINY, tinyUnicode8Px);
        map.put(PxdFont.FROST, frostFont4Px);
        map.put(PxdFont.GRINCHED, grinched7Px);
        this.fonts = Collections.unmodifiableMap(map);
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.CUSTOM;
    }

    @Override
    public List<BufferedImage> renderFrames(final CustomScreenConfig screenConfig) {
        return compileFrames(PxdDesign.parse(screenConfig.design()));
    }

    @Override
    public Optional<BufferedImage> renderScreen(final CustomScreenConfig screenConfig) {
        final PxdDesign design = PxdDesign.parse(screenConfig.design());
        return Optional.of(compileFrame(design.frames().getFirst()));
    }

    /**
     * Server-truth preview (spec §5.9/§3.6): baked frames for frame designs, and for
     * parametric designs the {@link AcmdMirror}'s timeline sampled on the command tick —
     * the exact Java model of what an ACMD panel renders.
     */
    public FrameStream renderPreview(final String design) {
        final PxdDesign parsed = PxdDesign.parse(design);
        if (parsed.hasParametrics()) {
            final AcmdMirror mirror = AcmdMirror.parse(renderCommandBatchOf(parsed));
            final long windowMs = previewWindowMs(parsed);
            final List<BufferedImage> frames = new ArrayList<>((int) (windowMs / COMMAND_PREVIEW_TICK_MS));
            for (long t = 0; t < windowMs; t += COMMAND_PREVIEW_TICK_MS) {
                frames.add(AcmdMirror.toBufferedImage(mirror.frameAt(t)));
            }
            return new FrameStream(frames, COMMAND_PREVIEW_TICK_MS);
        }
        return new FrameStream(compileFrames(parsed), parsed.frameDelayMs());
    }

    public Font awtFont(final PxdFont font) {
        return fonts.get(font);
    }

    /* ------------------------------------------------------------------
     * ACMD command path (spec §3.6): only parametric designs compile to
     * commands — static/animation designs keep the retained-frame / ANIM
     * pipelines (retained frames serve booting panels; ANIM persists).
     * ------------------------------------------------------------------ */

    @Override
    public boolean commandCapable(final CustomScreenConfig screenConfig) {
        return PxdDesign.parse(screenConfig.design()).hasParametrics();
    }

    @Override
    public byte[] renderCommandBatch(final CustomScreenConfig screenConfig) {
        final PxdDesign design = PxdDesign.parse(screenConfig.design());
        if (!design.hasParametrics()) {
            // The job gates on commandCapable; reaching here is a wiring bug.
            throw new IllegalStateException("only parametric designs compile to commands");
        }
        return renderCommandBatchOf(design);
    }

    private byte[] renderCommandBatchOf(final PxdDesign design) {
        final List<PxdDesign.Layer> layers = design.frames().getFirst().layers();

        // Distinct fonts in first-use order become ACMD FONT pages 0..n-1 (validation
        // caps them at 4). Pages draw nothing, so hoisting them in front of every layer
        // satisfies "FONT must precede its TEXT/SCROLL" without touching draw order.
        final Map<PxdFont, Integer> pageIds = new LinkedHashMap<>();
        for (final PxdDesign.Layer layer : layers) {
            final PxdFont font = switch (layer) {
                case PxdDesign.TextLayer text -> text.font();
                case PxdDesign.ScrollLayer scroll -> scroll.font();
                default -> null;
            };
            if (font != null) {
                pageIds.putIfAbsent(font, pageIds.size());
            }
        }

        final CommandBatch batch = CommandBatch.builder().cls(AcmdMirror.BLACK);
        pageIds.forEach((font, pageId) -> batch.fontPage(pageId, fontPage(font).glyphs()));
        for (final PxdDesign.Layer layer : layers) {
            appendLayer(batch, layer, pageIds);
        }
        return batch.build();
    }

    private void appendLayer(final CommandBatch batch, final PxdDesign.Layer layer,
                             final Map<PxdFont, Integer> pageIds) {
        switch (layer) {
            case PxdDesign.BitmapLayer bitmap -> batch.blit(0, 0, bitmap.image().getWidth(),
                    bitmap.image().getHeight(), blitPixels(bitmap.image()));
            case PxdDesign.TextLayer text -> {
                final String ascii = acmdText(text.text());
                if (ascii.isEmpty()) {
                    return; // nothing ASCII-foldable: the pixel font draws nothing either
                }
                batch.text(pageIds.get(text.font()), clampU8(text.x()), lineTopY(text.font(), text.y()),
                        Rgb565.of(text.color()), ascii);
            }
            case PxdDesign.RectLayer rect -> {
                final int x = Math.max(0, rect.x()), y = Math.max(0, rect.y());
                final int w = Math.min(64, rect.x() + rect.w()) - x;
                final int h = Math.min(32, rect.y() + rect.h()) - y;
                if (w >= 1 && h >= 1) {
                    if (rect.filled()) {
                        batch.fill(x, y, w, h, Rgb565.of(rect.color()));
                    } else {
                        batch.rect(x, y, w, h, Rgb565.of(rect.color()));
                    }
                }
            }
            case PxdDesign.LineLayer line -> batch.line(clampU8(line.x1()), clampU8(line.y1()),
                    clampU8(line.x2()), clampU8(line.y2()), Rgb565.of(line.color()));
            case PxdDesign.CircleLayer circle -> {
                if (circle.r() == 0) {
                    batch.pix(circle.cx(), circle.cy(), Rgb565.of(circle.color()));
                } else {
                    batch.circ(circle.cx(), circle.cy(), circle.r(), Rgb565.of(circle.color()));
                }
            }
            case PxdDesign.SweepLayer sweep -> batch.sweep(sweep.cx(), sweep.cy(), sweep.r(),
                    Rgb565.of(sweep.color()), sweep.speedDegPerSec());
            case PxdDesign.ScrollLayer scroll -> batch.scroll(scroll.x(),
                    lineTopY(scroll.font(), scroll.y()), scroll.w(), scroll.h(),
                    pageIds.get(scroll.font()), Rgb565.of(scroll.color()), scroll.speedMsPerPx(),
                    acmdText(scroll.text()).isEmpty() ? "?" : acmdText(scroll.text()));
            case PxdDesign.BlinkLayer blink -> batch.blink(blink.x(), blink.y(), blink.w(), blink.h(),
                    blink.periodMs());
        }
    }

    /** ACMD TEXT/SCROLL y = the frame path's baseline (pxd y + ascent) + the page's lineTop. */
    private int lineTopY(final PxdFont font, final int y) {
        return clampU8(y + ascent(font) + fontPage(font).lineTop());
    }

    private static int clampU8(final int v) {
        return Math.max(0, Math.min(255, v));
    }

    /** The bitmap as BLIT pixels: alpha-composited over black (the frame path's canvas) first. */
    private int[] blitPixels(final BufferedImage image) {
        final BufferedImage composited = paintToolsService.newImage();
        paintToolsService.drawImage(composited, image, 0, 0);
        final int[] pixels = new int[image.getWidth() * image.getHeight()];
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                pixels[y * image.getWidth() + x] = Rgb565.of(new Color(composited.getRGB(x, y)));
            }
        }
        return pixels;
    }

    /** Latin-folded, then reduced to ACMD's ASCII 32..126 (unrepresentable chars drop). */
    private static String acmdText(final String text) {
        final String folded = LatinFoldService.fold(text);
        final StringBuilder out = new StringBuilder(folded.length());
        for (final char c : folded.toCharArray()) {
            if (c >= 32 && c <= 126) {
                out.append(c);
            }
        }
        return out.toString();
    }

    private FontPageExtractor.FontPage fontPage(final PxdFont font) {
        return fontPages.computeIfAbsent(font, f -> FontPageExtractor.extract(fonts.get(f)));
    }

    private int ascent(final PxdFont font) {
        return ascents.computeIfAbsent(font, f -> metrics(f).getAscent());
    }

    private FontMetrics metrics(final PxdFont font) {
        // Same image type the frame path measures on (TYPE_INT_RGB), so the baselines match.
        return paintToolsService.newImage().getGraphics().getFontMetrics(fonts.get(font));
    }

    /** Preview window: the slowest parametric loop, clamped (spec §3.6). */
    private long previewWindowMs(final PxdDesign design) {
        long window = 0;
        for (final PxdDesign.Layer layer : design.frames().getFirst().layers()) {
            if (layer instanceof PxdDesign.SweepLayer sweep) {
                window = Math.max(window, (360_000L + sweep.speedDegPerSec() - 1) / sweep.speedDegPerSec());
            } else if (layer instanceof PxdDesign.BlinkLayer blink) {
                window = Math.max(window, blink.periodMs());
            } else if (layer instanceof PxdDesign.ScrollLayer scroll) {
                window = Math.max(window, scrollLoopMs(scroll));
            }
        }
        return Math.max(COMMAND_PREVIEW_MIN_MS, Math.min(COMMAND_PREVIEW_MAX_MS, window));
    }

    private long scrollLoopMs(final PxdDesign.ScrollLayer scroll) {
        final int textW = fontPage(scroll.font()).width(acmdText(scroll.text()));
        final int travel = Math.max(0, textW - scroll.w());
        if (travel < 1) {
            return 0; // static: no loop to show
        }
        // Mirror's ping-pong cycle: 2 passes (≥ SCROLL_MIN_PASS_PX units each) + 2 holds.
        final long passUnits = Math.max(travel, 12);
        final long units = 2 * passUnits + 2 * 8;
        return units * Math.max(1, scroll.speedMsPerPx());
    }

    /* ------------------------------------------------------------------
     * Frame compile (all designs; parametric layers bake their t=0 pose,
     * spec §3.6 — thumbnail and preview render)
     * ------------------------------------------------------------------ */

    private List<BufferedImage> compileFrames(final PxdDesign design) {
        return design.frames().stream()
                .map(this::compileFrame)
                .toList();
    }

    BufferedImage compileFrame(final PxdDesign.PxdFrame frame) {
        final BufferedImage image = paintToolsService.newImage();
        for (final PxdDesign.Layer layer : frame.layers()) {
            drawLayer(image, layer);
        }
        return image;
    }

    private void drawLayer(final BufferedImage image, final PxdDesign.Layer layer) {
        switch (layer) {
            case PxdDesign.BitmapLayer bitmap -> paintToolsService.drawImage(image, bitmap.image(), 0, 0);
            case PxdDesign.TextLayer text -> {
                final Font font = fonts.get(text.font());
                final int baseline = text.y() + image.getGraphics().getFontMetrics(font).getAscent();
                paintToolsService.drawText(image, font, text.text(), text.x(), baseline, text.color());
            }
            case PxdDesign.RectLayer rect -> {
                final Graphics2D g = (Graphics2D) image.getGraphics();
                g.setColor(rect.color());
                if (rect.filled()) {
                    g.fillRect(rect.x(), rect.y(), rect.w(), rect.h());
                } else {
                    g.drawRect(rect.x(), rect.y(), rect.w(), rect.h());
                }
                g.dispose();
            }
            case PxdDesign.LineLayer line -> {
                final Graphics2D g = (Graphics2D) image.getGraphics();
                g.setColor(line.color());
                g.drawLine(line.x1(), line.y1(), line.x2(), line.y2());
                g.dispose();
            }
            case PxdDesign.CircleLayer circle -> drawCircle(image, circle);
            case PxdDesign.SweepLayer sweep -> {
                // Baked t=0 pose: the θ=0 (east) line.
                final Graphics2D g = (Graphics2D) image.getGraphics();
                g.setColor(sweep.color());
                g.drawLine(sweep.cx(), sweep.cy(), sweep.cx() + sweep.r(), sweep.cy());
                g.dispose();
            }
            case PxdDesign.ScrollLayer scroll -> {
                // Baked head-hold pose: the folded text at x, clipped to the region.
                final Font font = fonts.get(scroll.font());
                final Graphics2D g = (Graphics2D) image.getGraphics();
                final int baseline = scroll.y() + g.getFontMetrics(font).getAscent();
                g.setClip(scroll.x(), scroll.y(), scroll.w(), scroll.h());
                g.setColor(scroll.color());
                g.setFont(font);
                g.drawString(LatinFoldService.fold(scroll.text()), scroll.x(), baseline);
                g.dispose();
            }
            case PxdDesign.BlinkLayer ignored -> { /* content phase: base stays visible */
            }
        }
    }

    private void drawCircle(final BufferedImage image, final PxdDesign.CircleLayer circle) {
        if (circle.r() == 0) {
            setPixel(image, circle.cx(), circle.cy(), circle.color());
            return;
        }
        final Graphics2D g = (Graphics2D) image.getGraphics();
        g.setColor(circle.color());
        final int d = circle.r() * 2;
        if (circle.filled()) {
            g.fillOval(circle.cx() - circle.r(), circle.cy() - circle.r(), d, d);
        } else {
            g.drawOval(circle.cx() - circle.r(), circle.cy() - circle.r(), d, d);
        }
        g.dispose();
    }

    private static void setPixel(final BufferedImage image, final int x, final int y, final Color color) {
        if (x >= 0 && x < image.getWidth() && y >= 0 && y < image.getHeight()) {
            image.setRGB(x, y, color.getRGB());
        }
    }
}
