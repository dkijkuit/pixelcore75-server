package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.CustomScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Frame compilation from a parsed design, unit-tested without Spring: fonts are loaded
 * from the same assets/fonts files and sizes the PaintConfig beans use.
 */
class CustomScreenServiceTests {

    private final CustomScreenService service = new CustomScreenService(
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

    @Test
    void rendersBitmapOnlyFrame() {
        final BufferedImage bitmap = solidImage(64, 32, new Color(0, 255, 0));
        bitmap.setRGB(10, 5, new Color(255, 0, 0).getRGB());
        final CustomScreenConfig config = config(designWithLayer(
                "{\"type\":\"bitmap\",\"data\":\"" + pngDataUrl(bitmap) + "\"}"));

        final List<BufferedImage> frames = service.renderFrames(config);

        assertEquals(1, frames.size());
        assertEquals(64, frames.getFirst().getWidth());
        assertEquals(32, frames.getFirst().getHeight());
        assertEquals(new Color(255, 0, 0).getRGB(), frames.getFirst().getRGB(10, 5));
        assertEquals(new Color(0, 255, 0).getRGB(), frames.getFirst().getRGB(0, 0));
    }

    @Test
    void textRendersNonBlackPixels() {
        final CustomScreenConfig config = config(designWithLayer(
                "{\"type\":\"text\",\"text\":\"HELLO\",\"x\":2,\"y\":2,\"color\":\"#00FF00\",\"font\":\"CG_PIXEL\"}"));

        final BufferedImage frame = service.renderFrames(config).getFirst();

        assertTrue(hasNonBlackPixel(frame), "text layer should paint non-black pixels");
    }

    @Test
    void renderFramesReturnsOneImagePerFrame() {
        final String design = """
                {"schemaVersion":1,"name":"Anim","frameDelayMs":120,"frames":[
                  {"layers":[{"type":"rect","x":0,"y":0,"w":4,"h":4,"color":"#FF0000","filled":true}]},
                  {"layers":[{"type":"rect","x":8,"y":0,"w":4,"h":4,"color":"#0000FF","filled":true}]},
                  {"layers":[{"type":"rect","x":16,"y":0,"w":4,"h":4,"color":"#00FF00","filled":true}]}
                ]}""";
        final CustomScreenConfig config = config(design);

        final List<BufferedImage> frames = service.renderFrames(config);

        assertEquals(3, frames.size());
        assertTrue(config.producesFrames());
        assertTrue(config.stageAhead());
        assertEquals(new Color(255, 0, 0).getRGB(), frames.get(0).getRGB(1, 1));
        assertEquals(new Color(0, 0, 255).getRGB(), frames.get(1).getRGB(9, 1));
        assertEquals(new Color(0, 255, 0).getRGB(), frames.get(2).getRGB(17, 1));
    }

    @Test
    void singleFrameDesignTakesStaticPath() {
        final CustomScreenConfig config = config(designWithLayer(
                "{\"type\":\"rect\",\"x\":0,\"y\":0,\"w\":4,\"h\":4,\"color\":\"#FF0000\",\"filled\":true}"));

        assertFalse(config.producesFrames());

        final Optional<BufferedImage> screen = service.renderScreen(config);
        assertTrue(screen.isPresent());
        assertEquals(new Color(255, 0, 0).getRGB(), screen.get().getRGB(1, 1));
    }

    @Test
    void frameDelayDelegatesToDesign() {
        assertEquals(250, config("{\"schemaVersion\":1,\"name\":\"T\",\"frameDelayMs\":250,\"frames\":[{\"layers\":[]}]}")
                .frameDelayMs());
        assertEquals(100, config("{\"schemaVersion\":1,\"name\":\"T\",\"frames\":[{\"layers\":[]}]}")
                .frameDelayMs());
    }

    @Test
    void shapesDrawAndClipSilently() {
        final String design = """
                {"schemaVersion":1,"name":"Shapes","frames":[{"layers":[
                  {"type":"line","x1":0,"y1":0,"x2":10,"y2":0,"color":"#FFFFFF"},
                  {"type":"circle","cx":30,"cy":15,"r":0,"color":"#0000FF","filled":false},
                  {"type":"rect","x":-5,"y":-5,"w":8,"h":8,"color":"#00FFFF","filled":true},
                  {"type":"line","x1":60,"y1":30,"x2":1000,"y2":1000,"color":"#FFFFFF"}
                ]}]}""";

        final BufferedImage frame = service.renderFrames(config(design)).getFirst();

        assertEquals(new Color(255, 255, 255).getRGB(), frame.getRGB(5, 0));
        assertEquals(new Color(0, 0, 255).getRGB(), frame.getRGB(30, 15));
        assertEquals(new Color(0, 255, 255).getRGB(), frame.getRGB(0, 0));
    }

    @Test
    void circleOutlineDrawsRingPixels() {
        final String design = designWithLayer(
                "{\"type\":\"circle\",\"cx\":16,\"cy\":16,\"r\":5,\"color\":\"#FF00FF\",\"filled\":false}");

        final BufferedImage frame = service.renderFrames(config(design)).getFirst();

        assertEquals(new Color(255, 0, 255).getRGB(), frame.getRGB(16, 11));
        assertEquals(new Color(255, 0, 255).getRGB(), frame.getRGB(16, 21));
        assertEquals(new Color(0, 0, 0).getRGB(), frame.getRGB(16, 16));
    }

    @Test
    void layersCompositeInArrayOrder() {
        final String design = """
                {"schemaVersion":1,"name":"Order","frames":[{"layers":[
                  {"type":"rect","x":0,"y":0,"w":4,"h":4,"color":"#FF0000","filled":true},
                  {"type":"rect","x":0,"y":0,"w":4,"h":4,"color":"#0000FF","filled":true}
                ]}]}""";

        final BufferedImage frame = service.renderFrames(config(design)).getFirst();

        assertEquals(new Color(0, 0, 255).getRGB(), frame.getRGB(1, 1));
    }

    private static CustomScreenConfig config(final String design) {
        return new CustomScreenConfig(ScreenType.CUSTOM, 10, design);
    }

    private static String designWithLayer(final String layer) {
        return "{\"schemaVersion\":1,\"name\":\"Test\",\"frames\":[{\"layers\":[" + layer + "]}]}";
    }

    private static boolean hasNonBlackPixel(final BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) != new Color(0, 0, 0).getRGB()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BufferedImage solidImage(final int w, final int h, final Color color) {
        final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                img.setRGB(x, y, color.getRGB());
            }
        }
        return img;
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
