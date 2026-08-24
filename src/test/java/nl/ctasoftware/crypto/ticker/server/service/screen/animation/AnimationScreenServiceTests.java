package nl.ctasoftware.crypto.ticker.server.service.screen.animation;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.AnimationScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Pure unit tests for the ANIMATION frame-count validation (2–60, server-validated;
 * the MQTT wire protocol itself still allows 2–200 for other screen types like radar).
 * The service is constructed directly, so the @Cacheable proxy is bypassed.
 */
class AnimationScreenServiceTests {

    private final AnimationScreenService service = new AnimationScreenService();

    @Test
    void acceptsBoundaryFrameCounts() {
        assertEquals(2, render(2).size());
        assertEquals(60, render(60).size());
    }

    @Test
    void rejectsSixtyOneFrames() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> render(61));
        assertEquals("Animation requires 2 to 60 frames, got 61", e.getMessage());
    }

    @Test
    void rejectsFewerThanTwoFrames() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> render(1));
        assertEquals("Animation requires 2 to 60 frames, got 1", e.getMessage());
    }

    @Test
    void rejectsNullFrames() {
        final IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> service.renderFrames(new AnimationScreenConfig(ScreenType.ANIMATION, 30, 100, null)));
        assertEquals("Animation requires 2 to 60 frames, got 0", e.getMessage());
    }

    @Test
    void frameLimitConstantsUnchangedExceptCap() {
        assertEquals(2, AnimationScreenService.MIN_FRAMES);
        assertEquals(60, AnimationScreenService.MAX_FRAMES);
        assertEquals(FrameScreenConfig.MIN_FRAME_DELAY_MS, AnimationScreenService.MIN_FRAME_DELAY_MS);
    }

    private List<BufferedImage> render(final int frameCount) {
        return service.renderFrames(new AnimationScreenConfig(ScreenType.ANIMATION, 30, 100, frames(frameCount)));
    }

    private static List<String> frames(final int count) {
        final String frame = frameDataUrl();
        return IntStream.range(0, count).mapToObj(i -> frame).toList();
    }

    private static String frameDataUrl() {
        try {
            final BufferedImage img = new BufferedImage(ImageService.W, ImageService.H, BufferedImage.TYPE_INT_RGB);
            final ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
