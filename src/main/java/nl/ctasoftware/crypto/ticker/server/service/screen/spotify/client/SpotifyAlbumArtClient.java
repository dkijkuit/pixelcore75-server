package nl.ctasoftware.crypto.ticker.server.service.screen.spotify.client;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.imageio.ImageIO;
import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves album-art thumbnails for the Spotify screen: downloads the cover once per
 * URL (Spotify's CDN, no auth), downscales to a {@value #THUMBNAIL_SIZE}&times;{@value
 * #THUMBNAIL_SIZE} area-averaged thumbnail and quantizes it to at most {@value
 * #PALETTE_LIMIT} RGB565 colors with Floyd-Steinberg dithering — the exact color set
 * ACMD BLIT can encode, and the exact pixels the frame path paints, so both render
 * paths show identical art (parity by construction).
 *
 * <p>Results (including failures, as empty) are cached per URL in a small LRU: the
 * render tick asks every second, but a track's art is fetched at most once — and a
 * dead URL must not re-download on every tick. Failures simply render the screen
 * without art.
 */
@Slf4j
@Service
public class SpotifyAlbumArtClient {

    public static final int THUMBNAIL_SIZE = 32;

    /** ACMD BLIT's PAL_RLE palette size — the hard ceiling for distinct colors. */
    static final int PALETTE_LIMIT = 16;

    private static final int CACHE_ENTRIES = 16;
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    /** A square RGB565 thumbnail: {@code size*size} u16 pixel values, row-major. */
    public record AlbumArt(int size, int[] rgb565) {

        public AlbumArt {
            if (size < 1 || rgb565 == null || rgb565.length != size * size) {
                throw new IllegalArgumentException("rgb565 must hold exactly size*size values");
            }
            for (final int c : rgb565) {
                if ((c & ~0xFFFF) != 0) {
                    throw new IllegalArgumentException("color " + c + " outside RGB565 u16 range");
                }
            }
        }
    }

    private final RestClient restClient;
    private final Map<String, Optional<AlbumArt>> cache;

    public SpotifyAlbumArtClient() {
        this.restClient = RestClient.create();
        this.cache = Collections.synchronizedMap(
                new LinkedHashMap<>(32, 0.75f, true) {
                    @Override
                    protected boolean removeEldestEntry(final Map.Entry<String, Optional<AlbumArt>> eldest) {
                        return size() > CACHE_ENTRIES;
                    }
                });
    }

    /**
     * The thumbnail for a cover URL, or null when unknown/unfetchable (cached either
     * way). Never throws — a missing cover degrades to the text-only layout.
     */
    public AlbumArt artFor(final String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        final Optional<AlbumArt> cached = cache.get(url);
        if (cached != null) {
            return cached.orElse(null);
        }
        final AlbumArt art = download(url);
        cache.put(url, Optional.ofNullable(art));
        return art;
    }

    private AlbumArt download(final String url) {
        try {
            final byte[] body = restClient.get().uri(URI.create(url)).retrieve().body(byte[].class);
            if (body == null || body.length == 0 || body.length > MAX_IMAGE_BYTES) {
                return null;
            }
            final BufferedImage src = ImageIO.read(new ByteArrayInputStream(body));
            if (src == null) {
                return null;
            }
            final BufferedImage thumb = new BufferedImage(THUMBNAIL_SIZE, THUMBNAIL_SIZE,
                    BufferedImage.TYPE_INT_RGB);
            final Graphics g = thumb.getGraphics();
            g.drawImage(src.getScaledInstance(THUMBNAIL_SIZE, THUMBNAIL_SIZE,
                    java.awt.Image.SCALE_AREA_AVERAGING), 0, 0, null);
            g.dispose();
            return new AlbumArt(THUMBNAIL_SIZE, quantizeDither(thumb));
        } catch (final Exception e) {
            log.debug("Spotify album art fetch failed for {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Quantizes an image (w&times;h, TYPE_INT_RGB) to at most {@value #PALETTE_LIMIT}
     * RGB565 colors: median-cut palette (averages rounded to the panel's nearest 5-6-5
     * representation, then expanded back so dithering decides against exactly the
     * colors the panel shows) with Floyd-Steinberg error diffusion. Deterministic.
     */
    static int[] quantizeDither(final BufferedImage image) {
        final int w = image.getWidth();
        final int h = image.getHeight();
        final int[] rgb = image.getRGB(0, 0, w, h, null, 0, w);
        final int[] palette888 = palette888(rgb, PALETTE_LIMIT);
        final int[] palette565 = new int[palette888.length];
        for (int i = 0; i < palette888.length; i++) {
            palette565[i] = rgb565(palette888[i]);
        }

        final double[] work = new double[w * h * 3];
        for (int i = 0; i < rgb.length; i++) {
            work[3 * i] = (rgb[i] >> 16) & 0xFF;
            work[3 * i + 1] = (rgb[i] >> 8) & 0xFF;
            work[3 * i + 2] = rgb[i] & 0xFF;
        }

        final int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                final int i = y * w + x;
                final double r = clamp255(work[3 * i]);
                final double gg = clamp255(work[3 * i + 1]);
                final double b = clamp255(work[3 * i + 2]);
                int best = 0;
                double bestDist = Double.MAX_VALUE;
                for (int p = 0; p < palette888.length; p++) {
                    final double dr = r - ((palette888[p] >> 16) & 0xFF);
                    final double dg = gg - ((palette888[p] >> 8) & 0xFF);
                    final double db = b - (palette888[p] & 0xFF);
                    final double dist = dr * dr + dg * dg + db * db;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = p;
                    }
                }
                out[i] = palette565[best];
                final double er = r - ((palette888[best] >> 16) & 0xFF);
                final double eg = gg - ((palette888[best] >> 8) & 0xFF);
                final double eb = b - (palette888[best] & 0xFF);
                diffuse(work, w, h, x + 1, y, 7.0 / 16, er, eg, eb);
                diffuse(work, w, h, x - 1, y + 1, 3.0 / 16, er, eg, eb);
                diffuse(work, w, h, x, y + 1, 5.0 / 16, er, eg, eb);
                diffuse(work, w, h, x + 1, y + 1, 1.0 / 16, er, eg, eb);
            }
        }
        return out;
    }

    /**
     * Median-cut palette of at most {@code maxColors} entries, each entry the box
     * average rounded to RGB565 and expanded back to 888 — the panel-representable
     * colors the dither pass commits to.
     */
    private static int[] palette888(final int[] rgb, final int maxColors) {
        List<List<Integer>> boxes = new ArrayList<>();
        boxes.add(new ArrayList<>(rgb.length));
        for (final int c : rgb) {
            boxes.getFirst().add(c);
        }
        while (boxes.size() < maxColors) {
            int splitAt = -1;
            int widest = -1;
            for (int i = 0; i < boxes.size(); i++) {
                final int span = widestChannelSpan(boxes.get(i));
                if (span > widest) {
                    widest = span;
                    splitAt = i;
                }
            }
            if (widest <= 0) {
                break; // every box is uniform: the palette already covers everything
            }
            final List<Integer> box = boxes.remove(splitAt);
            final int channel = widestChannel(box);
            box.sort((a, b) -> Integer.compare(channel(a, channel), channel(b, channel)));
            final List<Integer> left = new ArrayList<>(box.subList(0, box.size() / 2));
            final List<Integer> right = new ArrayList<>(box.subList(box.size() / 2, box.size()));
            boxes.add(left);
            boxes.add(right);
        }
        final int[] palette = new int[boxes.size()];
        for (int i = 0; i < boxes.size(); i++) {
            long r = 0;
            long g = 0;
            long b = 0;
            final List<Integer> box = boxes.get(i);
            for (final int c : box) {
                r += (c >> 16) & 0xFF;
                g += (c >> 8) & 0xFF;
                b += c & 0xFF;
            }
            palette[i] = expand565(rgb565((int) Math.round(r / (double) box.size()),
                    (int) Math.round(g / (double) box.size()), (int) Math.round(b / (double) box.size())));
        }
        return palette;
    }

    private static int widestChannelSpan(final List<Integer> box) {
        int best = 0;
        for (int channel = 0; channel < 3; channel++) {
            int min = 255;
            int max = 0;
            for (final int c : box) {
                final int v = channel(c, channel);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            best = Math.max(best, max - min);
        }
        return best;
    }

    private static int widestChannel(final List<Integer> box) {
        int bestChannel = 0;
        int bestSpan = -1;
        for (int channel = 0; channel < 3; channel++) {
            int min = 255;
            int max = 0;
            for (final int c : box) {
                final int v = channel(c, channel);
                min = Math.min(min, v);
                max = Math.max(max, v);
            }
            if (max - min > bestSpan) {
                bestSpan = max - min;
                bestChannel = channel;
            }
        }
        return bestChannel;
    }

    private static int channel(final int rgb, final int channel) {
        return (rgb >> (16 - 8 * channel)) & 0xFF;
    }

    private static void diffuse(final double[] work, final int w, final int h, final int x, final int y,
                                final double factor, final double er, final double eg, final double eb) {
        if (x < 0 || x >= w || y < 0 || y >= h) {
            return;
        }
        final int i = (y * w + x) * 3;
        work[i] += er * factor;
        work[i + 1] += eg * factor;
        work[i + 2] += eb * factor;
    }

    private static double clamp255(final double v) {
        return v < 0 ? 0 : Math.min(255, v);
    }

    private static int rgb565(final int rgb888) {
        return rgb565((rgb888 >> 16) & 0xFF, (rgb888 >> 8) & 0xFF, rgb888 & 0xFF);
    }

    /** The same nearest 5-6-5 quantization the frame path applies when publishing. */
    private static int rgb565(final int r, final int g, final int b) {
        return ((r * 31 + 127) / 255) << 11 | ((g * 63 + 127) / 255) << 5 | (b * 31 + 127) / 255;
    }

    /** RGB565 &rarr; RGB888 by replicating the high bits (AcmdMirror's preview bridge). */
    public static int expand565(final int c565) {
        final int r5 = (c565 >> 11) & 0x1F;
        final int g6 = (c565 >> 5) & 0x3F;
        final int b5 = c565 & 0x1F;
        return ((r5 << 3) | (r5 >> 2)) << 16 | ((g6 << 2) | (g6 >> 4)) << 8 | (b5 << 3) | (b5 >> 2);
    }
}
