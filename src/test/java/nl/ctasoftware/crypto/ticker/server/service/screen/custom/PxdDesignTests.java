package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * .pxd v1/v2 parse/validation rules (spec §3.4), unit-tested without Spring: the same
 * parse runs at save, render, and preview time.
 */
class PxdDesignTests {

    @Test
    void parsesMinimalValidDesign() {
        final PxdDesign design = PxdDesign.parse("{\"schemaVersion\":1,\"name\":\"Test\",\"frames\":[{\"layers\":[]}]}");

        assertEquals("Test", design.name());
        assertEquals(PxdDesign.DEFAULT_FRAME_DELAY_MS, design.frameDelayMs());
        assertEquals(1, design.frames().size());
        assertEquals(0, design.frames().getFirst().layers().size());
    }

    @Test
    void appliesExplicitFrameDelayAndFonts() {
        final PxdDesign design = PxdDesign.parse("""
                {"schemaVersion":1,"name":"Test","frameDelayMs":250,"frames":[{"layers":[
                  {"type":"text","text":"A","x":0,"y":0,"color":"#00FF00"},
                  {"type":"text","text":"B","x":0,"y":8,"color":"#00FF00","font":"LED_BOARD"}
                ]}]}""");

        assertEquals(250, design.frameDelayMs());
        assertEquals(PxdFont.CG_PIXEL, design.frames().getFirst().layers().get(0) instanceof PxdDesign.TextLayer t
                ? t.font() : null);
        assertEquals(PxdFont.LED_BOARD, design.frames().getFirst().layers().get(1) instanceof PxdDesign.TextLayer t
                ? t.font() : null);
    }

    @Test
    void ignoresUnknownPropertiesAtAllLevels() {
        final PxdDesign design = PxdDesign.parse("""
                {"schemaVersion":1,"name":"Test","futureSection":{"anything":1},
                 "frames":[{"onionSkin":true,"layers":[{"type":"rect","reserved":7,
                   "x":1,"y":1,"w":2,"h":2,"color":"#FF0000","filled":true}]}]}""");

        assertEquals(1, design.frames().size());
        assertEquals(1, design.frames().getFirst().layers().size());
    }

    @Test
    void rejectsWrongSchemaVersion() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"schemaVersion\":3,\"name\":\"Test\",\"frames\":[]}"));
        assertEquals("Unsupported schemaVersion 3, expected 1 or 2", e.getMessage());
    }

    @Test
    void rejectsMissingSchemaVersion() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"name\":\"Test\",\"frames\":[]}"));
        assertEquals("schemaVersion must be 1 or 2", e.getMessage());
    }

    @Test
    void rejectsInvalidColor() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer("{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":2,\"h\":2,\"color\":\"FF0000\"}")));
        assertEquals("frame 0: invalid color 'FF0000', expected #RRGGBB", e.getMessage());
    }

    @Test
    void rejectsTooManyFrames() {
        final String frames = IntStream.range(0, 61)
                .mapToObj(i -> "{\"layers\":[]}")
                .collect(Collectors.joining(","));
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"schemaVersion\":1,\"name\":\"Test\",\"frames\":[" + frames + "]}"));
        assertEquals("frames must contain 1 to 60 frames, got 61", e.getMessage());
    }

    @Test
    void rejectsZeroFrames() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"schemaVersion\":1,\"name\":\"Test\",\"frames\":[]}"));
        assertEquals("frames must contain 1 to 60 frames, got 0", e.getMessage());
    }

    @Test
    void rejectsUnknownFont() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer(
                        "{\"type\":\"text\",\"text\":\"HI\",\"x\":0,\"y\":0,\"color\":\"#FFFFFF\",\"font\":\"COMIC\"}")));
        assertEquals("frame 0: unknown font 'COMIC', must be one of "
                + java.util.List.of(PxdFont.values()), e.getMessage());
    }

    @Test
    void rejectsBitmapWrongSize() {
        final String dataUrl = pngDataUrl(32, 32);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer("{\"type\":\"bitmap\",\"data\":\"" + dataUrl + "\"}")));
        assertEquals("frame 0: bitmap is 32x32, expected 64x32", e.getMessage());
    }

    @Test
    void acceptsFullCanvasBitmap() {
        final String dataUrl = pngDataUrl(64, 32);
        final PxdDesign design = PxdDesign.parse(designWithLayer("{\"type\":\"bitmap\",\"data\":\"" + dataUrl + "\"}"));

        assertEquals(1, design.frames().size());
        assertEquals(PxdDesign.BitmapLayer.class, design.frames().getFirst().layers().getFirst().getClass());
    }

    @Test
    void rejectsUnknownLayerType() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer("{\"type\":\"triangle\",\"x\":0}")));
        assertEquals("frame 0: unknown layer type 'triangle'", e.getMessage());
    }

    @Test
    void rejectsEmptyAndTooLongText() {
        final IllegalArgumentException empty = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer(
                        "{\"type\":\"text\",\"text\":\"\",\"x\":0,\"y\":0,\"color\":\"#FFFFFF\"}")));
        assertEquals("frame 0: text must be 1 to 255 characters", empty.getMessage());

        final String longText = "a".repeat(256);
        final IllegalArgumentException tooLong = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer(
                        "{\"type\":\"text\",\"text\":\"" + longText + "\",\"x\":0,\"y\":0,\"color\":\"#FFFFFF\"}")));
        assertEquals("frame 0: text must be 1 to 255 characters", tooLong.getMessage());
    }

    @Test
    void rejectsNonPositiveRect() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer(
                        "{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":0,\"h\":2,\"color\":\"#FF0000\"}")));
        assertEquals("frame 0: rect w and h must be at least 1", e.getMessage());
    }

    @Test
    void rejectsNegativeCircleRadius() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer(
                        "{\"type\":\"circle\",\"cx\":10,\"cy\":10,\"r\":-1,\"color\":\"#0000FF\"}")));
        assertEquals("frame 0: circle r must be at least 0", e.getMessage());
    }

    @Test
    void rejectsFrameDelayOutOfRange() {
        final IllegalArgumentException low = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"schemaVersion\":1,\"name\":\"T\",\"frameDelayMs\":9,\"frames\":[{\"layers\":[]}]}"));
        assertEquals("frameDelayMs must be an integer between 10 and 65535", low.getMessage());

        final IllegalArgumentException high = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"schemaVersion\":1,\"name\":\"T\",\"frameDelayMs\":65536,\"frames\":[{\"layers\":[]}]}"));
        assertEquals("frameDelayMs must be an integer between 10 and 65535", high.getMessage());
    }

    @Test
    void rejectsInvalidJson() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("not json"));
        assertEquals("Design is not valid JSON", e.getMessage());
    }

    @Test
    void rejectsNonObjectDesign() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("[1,2]"));
        assertEquals("Design must be a JSON object", e.getMessage());
    }

    @Test
    void rejectsOversizedDesign() {
        final String padding = "a".repeat(PxdDesign.MAX_DESIGN_BYTES);
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse("{\"schemaVersion\":1,\"name\":\"T\",\"reserved\":\"" + padding + "\"}"));
        assertEquals("Design exceeds the 512KB limit", e.getMessage());
    }

    /* ---------------- v2 parametric layers (spec §3.6 / §3.4.7) ---------------- */

    @Test
    void parsesParametricLayers() {
        final PxdDesign design = PxdDesign.parse("""
                {"schemaVersion":2,"name":"Anim","frames":[{"layers":[
                  {"type":"sweep","cx":32,"cy":16,"r":15,"color":"#00FF00","speedDegPerSec":45},
                  {"type":"scroll","x":0,"y":24,"w":64,"h":8,"text":"HELLO","color":"#FFFFFF","speedMsPerPx":120},
                  {"type":"blink","x":28,"y":12,"w":8,"h":8,"periodMs":1000}
                ]}]}""");

        assertTrue(design.hasParametrics());
        assertEquals(3, design.parametricCount());
        final var layers = design.frames().getFirst().layers();
        assertEquals(PxdDesign.SweepLayer.class, layers.get(0).getClass());
        assertEquals(PxdDesign.ScrollLayer.class, layers.get(1).getClass());
        assertEquals(PxdFont.CG_PIXEL, ((PxdDesign.ScrollLayer) layers.get(1)).font());
        assertEquals(PxdDesign.BlinkLayer.class, layers.get(2).getClass());
    }

    @Test
    void rejectsParametricLayersInV1Design() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(designWithLayer(
                        "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":45}")));
        assertEquals("frame 0: layer type 'sweep' requires schemaVersion 2", e.getMessage());
    }

    @Test
    void acceptsV2DesignWithoutParametricLayers() {
        final PxdDesign design = PxdDesign.parse(v2DesignWithLayers(
                "{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"color\":\"#FF0000\",\"filled\":true}"));

        assertFalse(design.hasParametrics());
    }

    @Test
    void rejectsParametricDesignWithMultipleFrames() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2Frames(
                        "[{\"layers\":[{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":45}]},"
                                + "{\"layers\":[]}]")));
        assertEquals("parametric layers require a single frame, got 2", e.getMessage());
    }

    @Test
    void rejectsMoreThanFourParametrics() {
        final String sweep = "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":45}";
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(
                        sweep + "," + sweep + "," + sweep + "," + sweep + "," + sweep)));
        assertEquals("a design supports at most 4 parametric layers, got 5", e.getMessage());
    }

    @Test
    void rejectsMoreThanFourDistinctFontsWithParametrics() {
        final String layers = "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":5,\"color\":\"#00FF00\",\"speedDegPerSec\":45}"
                + ",{\"type\":\"text\",\"text\":\"A\",\"x\":0,\"y\":0,\"color\":\"#FFFFFF\",\"font\":\"CG_PIXEL\"}"
                + ",{\"type\":\"text\",\"text\":\"B\",\"x\":0,\"y\":6,\"color\":\"#FFFFFF\",\"font\":\"MINI_LINE\"}"
                + ",{\"type\":\"text\",\"text\":\"C\",\"x\":0,\"y\":12,\"color\":\"#FFFFFF\",\"font\":\"HABBO\"}"
                + ",{\"type\":\"text\",\"text\":\"D\",\"x\":0,\"y\":18,\"color\":\"#FFFFFF\",\"font\":\"LED_BOARD\"}"
                + ",{\"type\":\"text\",\"text\":\"E\",\"x\":0,\"y\":24,\"color\":\"#FFFFFF\",\"font\":\"TINY\"}";
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(layers)));
        assertEquals("a design with parametric layers supports at most 4 distinct fonts "
                + "across text and scroll layers, got 5", e.getMessage());
    }

    @Test
    void rejectsParametricBitmapWithTooManyColors() {
        final BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_RGB);
        for (int i = 0; i < 17; i++) { // 17 distinct colors > BLIT's 16-entry palette
            img.setRGB(i * 3, 0, 0xFF000000 | (i * 15 << 16) | (i * 7 << 8) | i * 3);
        }
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(
                        "{\"type\":\"bitmap\",\"data\":\"" + pngDataUrl(img) + "\"},"
                                + "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":45}")));
        assertEquals("frame 0: bitmap has more than 16 distinct RGB565 colors, too many for a parametric design",
                e.getMessage());
    }

    @Test
    void rejectsOutOfRangeParametricSpeeds() {
        final IllegalArgumentException speed = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(
                        "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":256}")));
        assertEquals("frame 0: sweep speedDegPerSec must be between 1 and 255", speed.getMessage());

        final IllegalArgumentException scrollSpeed = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(
                        "{\"type\":\"scroll\",\"x\":0,\"y\":24,\"w\":64,\"h\":8,\"text\":\"A\",\"color\":\"#FFFFFF\",\"speedMsPerPx\":0}")));
        assertEquals("frame 0: scroll speedMsPerPx must be between 1 and 65535", scrollSpeed.getMessage());

        final IllegalArgumentException period = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(
                        "{\"type\":\"blink\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"periodMs\":1}")));
        assertEquals("frame 0: blink periodMs must be between 2 and 65535", period.getMessage());
    }

    @Test
    void rejectsNegativeCoordinatesInParametricDesign() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PxdDesign.parse(v2DesignWithLayers(
                        "{\"type\":\"sweep\",\"cx\":32,\"cy\":16,\"r\":15,\"color\":\"#00FF00\",\"speedDegPerSec\":45},"
                                + "{\"type\":\"rect\",\"x\":-5,\"y\":0,\"w\":8,\"h\":8,\"color\":\"#00FFFF\",\"filled\":true}")));
        assertEquals("frame 0: coordinates of a parametric design must be within 0..255", e.getMessage());
    }

    private static String v2DesignWithLayers(final String layers) {
        return v2Frames("[{\"layers\":[" + layers + "]}]");
    }

    private static String v2Frames(final String frames) {
        return "{\"schemaVersion\":2,\"name\":\"Test\",\"frames\":" + frames + "}";
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

    private static String designWithLayer(final String layer) {
        return "{\"schemaVersion\":1,\"name\":\"Test\",\"frames\":[{\"layers\":[" + layer + "]}]}";
    }

    private static String pngDataUrl(final int w, final int h) {
        try {
            final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
