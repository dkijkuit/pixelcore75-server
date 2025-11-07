package nl.ctasoftware.crypto.ticker.server.service.screen.image;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.Optional;

@Slf4j
@Service
public class ImageScreenService implements ScreenService<ImageScreenConfig> {
    final ImageService imageService;

    public ImageScreenService(final ImageService imageService) {
        this.imageService = imageService;
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.IMAGE;
    }

    @Override
    @Cacheable("images")
    public Optional<BufferedImage> renderScreen(ImageScreenConfig screenConfig) {
        log.info("Loading image: {}", screenConfig.image());

        return Optional.of(fromBase64(screenConfig.imageUploadData()));
    }

    public BufferedImage fromBase64(String imageUploadData) {
        String base64Image = imageUploadData.split(",")[1];

        byte[] imageBytes = Base64.getDecoder().decode(base64Image);
        try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
            return ImageIO.read(bis);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
