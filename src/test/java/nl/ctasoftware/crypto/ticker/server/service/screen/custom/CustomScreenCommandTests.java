package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdCommand;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdParser;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.FrameScreenService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CUSTOM screen's ACMD command path (pxd spec §3.6): eligibility gating, batch
 * structure (CLS start, FONT pages hoisted before their TEXT/SCROLL, one command per
 * layer in order), golden-image parity of the baked t=0 pose vs the {@link AcmdMirror},
 * the parametric timeline (sweep rotation, blink phases, scroll movement) and the sampled
 * preview — docker-free, fonts loaded from the same assets/fonts files and sizes the
 * PaintConfig beans use.
 *
 * <p>Parity tolerance: budget 160 of 2048 px (the radar class); measured 0 — shape/text
 * layers map 1:1 and the bitmap BLIT is byte-exact, the budget only guards JDK
 * font-rendering variance.</p>
 */
class CustomScreenCommandTests {

    /** Same 5% budget class as CLOCK/LIST; measured well below (shapes/text are 1:1). */
    private static final int MISMATCH_BUDGET = 160;

    private static CustomScreenService service;

    @BeforeAll
    static void setUp() {
        service = new CustomScreenService(
                new PaintToolsService(font("assets/fonts/Habbo.ttf", 16f),
                        font("assets/fonts/MiniLine2.ttf", 8f),
                        font("assets/fonts/EXEPixelPerfect.ttf", 16f)),
                font("assets/fonts/cg-pixel-4x5.ttf", 5f),
                font("assets/fonts/MiniLine2.ttf", 8f),
                font("assets/fonts/Habbo.ttf", 16f),
                font("assets/fonts/EXEPixelPerfect.ttf", 16f),
                font("assets/fonts/TinyUnicode.ttf", 16f),
                font("assets/fonts/frostfont-logo.ttf", 7f),
                font("assets/fonts/grinched-4x7.ttf", 9f));
    }

    /* ---------------- eligibility (job gating) ---------------- */

    @Test
    void onlyParametricDesignsAreCommandCapable() {
        assertFalse(service.commandCapable(config(v2Design(
                "{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"color\":\"#FF0000\",\"filled\":true}"))));
        assertTrue(service.commandCapable(config(v2Design(sweepLayer()))));
    }

    @Test
    void nonParametricDesignsRefuseCommandCompile() {
        final IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.renderCommandBatch(config(v2Design(
                        "{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"color\":\"#FF0000\",\"filled\":true}"))));
        assertEquals("only parametric designs compile to commands", e.getMessage());
    }

    /* ---------------- batch structure ---------------- */

    @Test
    void batchMapsLayersInOrderWithHoistedFontPages() {
        final String design = v2Design(
                "{\"type\":\"text\",\"text\":\"HELLO\",\"x\":2,\"y\":2,\"color\":\"#00FF00\",\"font\":\"LED_BOARD\"},"
                        + sweepLayer() + ","
                        + "{\"type\":\"scroll\",\"x\":0,\"y\":24,\"w\":64,\"h\":8,\"text\":\"WORLD\",\"font\":\"CG_PIXEL\",\"color\":\"#FFFFFF\",\"speedMsPerPx\":120},"
                        + "{\"type\":\"blink\",\"x\":28,\"y\":12,\"w\":8,\"h\":8,\"periodMs\":1000}");

        final byte[] batch = service.renderCommandBatch(config(design));
        final List<AcmdCommand> commands = AcmdParser.parse(batch).commands();

        assertEquals(AcmdCommand.Cls.class, commands.getFirst().getClass());
        // Two fonts (LED_BOARD text, CG_PIXEL scroll) → two hoisted FONT pages, then layers in order.
        assertEquals(AcmdCommand.FontPage.class, commands.get(1).getClass());
        assertEquals(AcmdCommand.FontPage.class, commands.get(2).getClass());
        assertEquals(0, ((AcmdCommand.FontPage) commands.get(1)).pageId());
        assertEquals(1, ((AcmdCommand.FontPage) commands.get(2)).pageId());
        assertEquals(AcmdCommand.Text.class, commands.get(3).getClass());
        assertEquals("HELLO", ((AcmdCommand.Text) commands.get(3)).ascii());
        assertEquals(0, ((AcmdCommand.Text) commands.get(3)).fontId());
        final AcmdCommand.Sweep sweep = assertInstanceOf(AcmdCommand.Sweep.class, commands.get(4));
        assertEquals(32, sweep.cx());
        assertEquals(16, sweep.cy());
        assertEquals(15, sweep.r());
        assertEquals(45, sweep.speedDegPerSec());
        final AcmdCommand.Scroll scroll = assertInstanceOf(AcmdCommand.Scroll.class, commands.get(5));
        assertEquals("WORLD", scroll.ascii());
        assertEquals(1, scroll.fontId());
        assertEquals(120, scroll.speedMsPerPx());
        final AcmdCommand.Blink blink = assertInstanceOf(AcmdCommand.Blink.class, commands.get(6));
        assertEquals(1000, blink.periodMs());

        final AcmdMirror mirror = AcmdMirror.parse(batch);
        assertEquals(3, mirror.parametricCount(), "sweep + scroll + blink all armed");
    }

    @Test
    void staticLayersMapToTheirCommands() {
        final String design = v2Design(
                "{\"type\":\"rect\",\"x\":1,\"y\":1,\"w\":6,\"h\":4,\"color\":\"#FF0000\",\"filled\":false},"
                        + "{\"type\":\"rect\",\"x\":10,\"y\":1,\"w\":6,\"h\":4,\"color\":\"#FF0000\",\"filled\":true},"
                        + "{\"type\":\"line\",\"x1\":0,\"y1\":31,\"x2\":63,\"y2\":31,\"color\":\"#FFFFFF\"},"
                        + "{\"type\":\"circle\",\"cx\":50,\"cy\":16,\"r\":6,\"color\":\"#0000FF\",\"filled\":false},"
                        + "{\"type\":\"circle\",\"cx\":58,\"cy\":16,\"r\":0,\"color\":\"#00FF00\",\"filled\":false},"
                        + sweepLayer());

        final List<AcmdCommand> commands = AcmdParser.parse(service.renderCommandBatch(config(design))).commands();

        assertEquals(AcmdCommand.Rect.class, commands.get(1).getClass());
        assertEquals(AcmdCommand.Fill.class, commands.get(2).getClass());
        assertEquals(AcmdCommand.Line.class, commands.get(3).getClass());
        assertEquals(AcmdCommand.Circ.class, commands.get(4).getClass());
        assertEquals(AcmdCommand.Pix.class, commands.get(5).getClass(), "r=0 maps to PIX (GFX drawCircle r=0 draws nothing)");
    }

    @Test
    void bitmapBlitsItsQuantizedPixels() {
        final BufferedImage bitmap = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 64; x++) {
                bitmap.setRGB(x, y, (x < 32) ? new Color(0, 255, 0).getRGB() : new Color(255, 0, 0).getRGB());
            }
        }
        final String design = v2Design(
                "{\"type\":\"bitmap\",\"data\":\"" + pngDataUrl(bitmap) + "\"}," + sweepLayer());

        final List<AcmdCommand> commands = AcmdParser.parse(service.renderCommandBatch(config(design))).commands();

        final AcmdCommand.Blit blit = assertInstanceOf(AcmdCommand.Blit.class, commands.get(1));
        assertEquals(64, blit.w());
        assertEquals(32, blit.h());
        assertEquals(0x07E0, blit.pixels()[0]);
        assertEquals(0xF800, blit.pixels()[64 * 31 + 32]);
    }

    /* ---------------- parity: baked t=0 pose vs the mirror ---------------- */

    @Test
    void bakedFrameMatchesTheCommandMirrorAtZero() {
        final String design = v2Design(
                "{\"type\":\"rect\",\"x\":2,\"y\":2,\"w\":60,\"h\":28,\"color\":\"#0000A0\",\"filled\":true},"
                        + "{\"type\":\"text\",\"text\":\"RADAR\",\"x\":3,\"y\":3,\"color\":\"#FFFF00\",\"font\":\"CG_PIXEL\"},"
                        + "{\"type\":\"circle\",\"cx\":32,\"cy\":16,\"r\":8,\"color\":\"#00FF00\",\"filled\":false},"
                        + "{\"type\":\"line\",\"x1\":32,\"y1\":16,\"x2\":63,\"y2\":16,\"color\":\"#FFFFFF\"},"
                        + sweepLayer() + ","
                        + "{\"type\":\"scroll\",\"x\":0,\"y\":24,\"w\":64,\"h\":8,\"text\":\"LIVE FEED\",\"font\":\"CG_PIXEL\",\"color\":\"#FFFFFF\",\"speedMsPerPx\":120},"
                        + "{\"type\":\"blink\",\"x\":28,\"y\":12,\"w\":8,\"h\":8,\"periodMs\":1000}");

        final CustomScreenConfig screenConfig = config(design);
        final Optional<BufferedImage> golden = service.renderScreen(screenConfig); // baked t=0 pose
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(screenConfig));

        final int mismatch = FrameParity.mismatchedPixels(
                FrameParity.rgb565(golden.orElseThrow()), mirror.frameAt(0));
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "CUSTOM t=0 parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    /* ---------------- parametric timeline ---------------- */

    @Test
    void sweepRotatesAndBlinkGoesDarkOnTheirSchedules() {
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config(v2Design(
                sweepLayer() + ",{\"type\":\"blink\",\"x\":28,\"y\":12,\"w\":8,\"h\":8,\"periodMs\":1000}"))));

        final int[] atZero = mirror.frameAt(0);
        assertTrue(pixelOn(atZero, 47, 16), "θ=0: sweep endpoint east of the center");
        assertFalse(pixelOn(atZero, 32, 31), "θ=0: nothing south yet");

        // speed 45°/s → quarter revolution (screen-down endpoint, math +y) at 2000 ms.
        final int[] atQuarter = mirror.frameAt(2000);
        assertTrue(pixelOn(atQuarter, 32, 31), "θ=90°: sweep endpoint at screen-down (y grows downward)");

        // blink periodMs 1000 → dark phase during the second half ([500,1000) ms): the
        // sweep's center pixel (inside the region, drawn before the blink overlay) is
        // cleared there and visible during the content phase.
        assertFalse(pixelOn(mirror.frameAt(600), 32, 16), "blink dark phase blanks the region");
        assertTrue(pixelOn(mirror.frameAt(100), 32, 16), "content phase keeps the region's base (sweep center)");
    }

    @Test
    void scrollLeavesTheHeadHoldAfterTheHoldPhase() {
        final AcmdMirror mirror = AcmdMirror.parse(service.renderCommandBatch(config(v2Design(
                "{\"type\":\"scroll\",\"x\":0,\"y\":24,\"w\":64,\"h\":8,\"text\":\"A VERY LONG SCROLLING LINE OF TEXT\",\"font\":\"CG_PIXEL\",\"color\":\"#FFFFFF\",\"speedMsPerPx\":120}"))));

        // Head hold = SCROLL_HOLD_PX (8) px-units × 120 ms = 960 ms of penX at x.
        final int[] duringHold = mirror.frameAt(500);
        final int[] afterHold = mirror.frameAt(5000);
        assertFalse(java.util.Arrays.equals(duringHold, afterHold),
                "the marquee must start travelling after the head hold");
    }

    /* ---------------- preview ---------------- */

    @Test
    void previewSamplesTheParametricTimelineOnTheCommandTick() {
        final String design = v2Design(sweepLayer()); // sweep loop = 360/45 s = 8000 ms

        final FrameScreenService.FrameStream preview = service.renderPreview(design);

        assertEquals(CustomScreenService.COMMAND_PREVIEW_TICK_MS, preview.frameDelayMs());
        assertEquals(80, preview.frames().size(), "8000 ms window at 100 ms");
        for (final BufferedImage frame : preview.frames()) {
            assertEquals(64, frame.getWidth());
            assertEquals(32, frame.getHeight());
        }
    }

    @Test
    void previewOfFrameDesignsStaysBaked() {
        final FrameScreenService.FrameStream preview = service.renderPreview("""
                {"schemaVersion":1,"name":"Anim","frameDelayMs":120,"frames":[
                  {"layers":[{"type":"rect","x":0,"y":0,"w":4,"h":4,"color":"#FF0000","filled":true}]},
                  {"layers":[{"type":"rect","x":8,"y":0,"w":4,"h":4,"color":"#0000FF","filled":true}]}
                ]}""");

        assertEquals(2, preview.frames().size());
        assertEquals(120, preview.frameDelayMs());
    }

    /* ---------------- helpers ---------------- */

    private static String sweepLayer() {
        return "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":45}";
    }

    private static String v2Design(final String layers) {
        return "{\"schemaVersion\":2,\"name\":\"Test\",\"frames\":[{\"layers\":[" + layers + "]}]}";
    }

    private static CustomScreenConfig config(final String design) {
        return new CustomScreenConfig(ScreenType.CUSTOM, 10, design);
    }

    private static boolean pixelOn(final int[] rgb565Frame, final int x, final int y) {
        return (rgb565Frame[y * 64 + x] & 0xFFFF) != 0x0000;
    }

    private static String pngDataUrl(final BufferedImage img) {
        try {
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Font font(final String file, final float size) {
        try {
            return Font.createFont(Font.TRUETYPE_FONT, new File(file)).deriveFont(size);
        } catch (final FontFormatException | IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
