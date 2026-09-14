package nl.ctasoftware.crypto.ticker.server.service.screen.animation;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.AnimationScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.FrameScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.screen.StaticScreenService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class AnimationScreenService implements StaticScreenService<AnimationScreenConfig> {
    public static final int MIN_FRAMES = 2;

    /**
     * Screen-type cap for GIF/ANIMATION screens (server validation; the frontend
     * auto-trims GIFs to this limit with a user hint). The MQTT wire protocol keeps
     * its 2–200 frame range for other frame-producing screens (e.g. radar).
     */
    public static final int MAX_FRAMES = 60;
    public static final int MIN_FRAME_DELAY_MS = FrameScreenConfig.MIN_FRAME_DELAY_MS;

    @Override
    public ScreenType getScreenType() {
        return ScreenType.ANIMATION;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final AnimationScreenConfig screenConfig) {
        return renderFrames(screenConfig).stream().findFirst();
    }

    // Digest key: the default key would be the whole config record (multi-MB base64
    // frame strings) — re-hashed on every lookup. Same frames → same key → same decode.
    @Cacheable(cacheNames = "animations", key = "@cacheKeys.digest(#screenConfig.frames())")
    public List<BufferedImage> renderFrames(final AnimationScreenConfig screenConfig) {
        final List<String> frames = screenConfig.frames();

        if (frames == null || frames.size() < MIN_FRAMES || frames.size() > MAX_FRAMES) {
            throw new IllegalArgumentException("Animation requires " + MIN_FRAMES + " to " + MAX_FRAMES +
                    " frames, got " + (frames == null ? 0 : frames.size()));
        }

        log.info("Decoding {} animation frames", frames.size());

        return frames.stream()
                .parallel()
                .map(this::decodeFrame)
                .toList();
    }

    private BufferedImage decodeFrame(final String dataUrl) {
        final String base64Image = dataUrl.contains(",")
                ? dataUrl.split(",", 2)[1]
                : dataUrl;

        final byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
            final BufferedImage img = ImageIO.read(bis);
            if (img == null) {
                throw new IllegalArgumentException("Invalid animation frame data");
            }
            if (img.getWidth() != ImageService.W || img.getHeight() != ImageService.H) {
                throw new IllegalArgumentException("Animation frame must be " + ImageService.W + "x" + ImageService.H +
                        ", got " + img.getWidth() + "x" + img.getHeight());
            }
            return img;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
