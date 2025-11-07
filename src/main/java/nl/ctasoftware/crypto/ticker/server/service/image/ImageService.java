package nl.ctasoftware.crypto.ticker.server.service.image;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;
import java.util.regex.Pattern;

@Slf4j
@Service
public class ImageService {
    public static final int W = 64;
    public static final int H = 32;
    public static final int FRAME_BYTES = W * H * 2;

    private static final Pattern DATA_URL = Pattern.compile(
            "^data:([\\w!#$&^_.+-]+/[\\w!#$&^_.+-]+);base64,(.+)$", Pattern.CASE_INSENSITIVE);

    public BufferedImage imageToBufferedImage(final String filename) {
        final File file = new File(filename);

        try {
            return ImageIO.read(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Convert 64x32 ARGB to raw RGB565 LE.
     * - Hard alpha threshold (no soft edges).
     * - Nearest quantization to 5-6-5 (less bias than truncation).
     * @param alphaThreshold 0..255, e.g. 128. Under threshold → background.
     * @param bgRGB background when transparent, e.g. 0x000000.
     */
    public byte[] bufferedImageToBytes(BufferedImage img, int alphaThreshold, int bgRGB) {
        if (img.getWidth() != W || img.getHeight() != H)
            throw new IllegalArgumentException("Image must be " + W + "x" + H);

        byte[] out = new byte[FRAME_BYTES];
        int idx = 0;

        for (int y = 0; y < H; y++) {
            for (int x = 0; x < W; x++) {
                int argb = img.getRGB(x, y);
                int rgb565 = getRgb565(alphaThreshold, bgRGB, argb);

                out[idx++] = (byte) (rgb565 & 0xFF);         // low byte
                out[idx++] = (byte) ((rgb565 >>> 8) & 0xFF); // high byte
            }
        }
        return out;
    }

    private int getRgb565(int alphaThreshold, int bgRGB, int argb) {
        int a = (argb >>> 24) & 0xFF;

        int r, g, b;
        if (a < alphaThreshold) {
            r = (bgRGB >>> 16) & 0xFF;
            g = (bgRGB >>> 8) & 0xFF;
            b = bgRGB & 0xFF;
        } else {
            r = (argb >>> 16) & 0xFF;
            g = (argb >>> 8)  & 0xFF;
            b = argb & 0xFF;
        }

        // Nearest mapping to 5/6/5 (adds 127 for rounding, not truncation)
        int r5 = (r * 31 + 127) / 255;
        int g6 = (g * 63 + 127) / 255;
        int b5 = (b * 31 + 127) / 255;

        return (r5 << 11) | (g6 << 5) | b5;
    }

    public BufferedImage scale(BufferedImage src, int width, int height) {
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = resized.createGraphics();

        // Use NEAREST_NEIGHBOR to keep pixels sharp
        g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        g2d.drawImage(src, 0, 0, width, height, null);
        g2d.dispose();

        return resized;
    }

    public record Upload(String contentType, byte[] bytes) {
    }

    public Upload decodeDataUrl(String dataUrl) {
        var m = DATA_URL.matcher(dataUrl);
        if (!m.matches()) throw new IllegalArgumentException("Invalid imageUploadDataUrl");
        return new Upload(m.group(1), Base64.getDecoder().decode(m.group(2)));
    }

    public void validate(ImageScreenConfig cfg) {
        if (cfg.image() == null || cfg.image().isBlank()) {
            throw new IllegalArgumentException("image (target filename) is required");
        }
        // Optional: limit extensions you accept
        if (!cfg.image().toLowerCase().matches(".*\\.(png|jpe?g|webp|gif)$")) {
            throw new IllegalArgumentException("Unsupported image extension (png, jpg, jpeg, webp, gif)");
        }

        // Remove prefix if present
        String base64Image = cfg.imageUploadData().contains(",")
                ? cfg.imageUploadData().split(",")[1]
                : cfg.imageUploadData();

        byte[] imageBytes = Base64.getDecoder().decode(base64Image);

        try (final ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
            final BufferedImage img = ImageIO.read(bis);
            if (img == null) {
                throw new IllegalArgumentException("Invalid image data");
            }
            if (img.getWidth() != 64 || img.getHeight() != 32) {
                throw new IllegalArgumentException(
                        "Invalid image dimensions: must be 64x32, got " +
                                img.getWidth() + "x" + img.getHeight()
                );
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
