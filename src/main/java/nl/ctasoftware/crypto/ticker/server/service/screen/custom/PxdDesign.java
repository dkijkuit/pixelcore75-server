package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parsed .pxd design, v1 or v2 (spec docs/custom-screens-design.md §3): a lenient JSON parse
 * (unknown properties ignored at every level, so files round-trip reserved future sections)
 * followed by explicit validation with user-readable messages. v2 = v1 + the parametric layer
 * types of §3.6 (sweep/scroll/blink). The same rules run identically at save, render, and
 * preview time — this parse is the single gate.
 */
public record PxdDesign(String name, int frameDelayMs, List<PxdFrame> frames) {

    public static final int MAX_DESIGN_BYTES = 512 * 1024;
    public static final int MAX_FRAMES = 60;
    public static final int DEFAULT_FRAME_DELAY_MS = 100;
    public static final int SCHEMA_VERSION = 1;
    public static final int SCHEMA_VERSION_PARAMETRIC = 2;
    /** ACMD limits a parametric design must fit (spec §3.4.7): armed parametrics and FONT pages. */
    public static final int MAX_PARAMETRICS = 4;
    public static final int MAX_COMMAND_FONTS = 4;
    public static final int MAX_BLIT_COLORS = 16;
    /** ACMD coordinates are u8; parametric designs (which compile to commands) must fit them. */
    public static final int MAX_COORD = 255;

    private static final ObjectMapper LENIENT = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern HEX_COLOR = Pattern.compile("^#[0-9a-fA-F]{6}$");

    public record PxdFrame(List<Layer> layers) {
    }

    public sealed interface Layer
            permits BitmapLayer, TextLayer, RectLayer, LineLayer, CircleLayer, SweepLayer, ScrollLayer, BlinkLayer {
    }

    public record BitmapLayer(BufferedImage image) implements Layer {
    }

    public record TextLayer(String text, int x, int y, Color color, PxdFont font) implements Layer {
    }

    public record RectLayer(int x, int y, int w, int h, Color color, boolean filled) implements Layer {
    }

    public record LineLayer(int x1, int y1, int x2, int y2, Color color) implements Layer {
    }

    public record CircleLayer(int cx, int cy, int r, Color color, boolean filled) implements Layer {
    }

    /** Parametric (v2 §3.6): rotating line, endpoint at θ = elapsed·speed/1000 mod 360. */
    public record SweepLayer(int cx, int cy, int r, Color color, int speedDegPerSec) implements Layer {
    }

    /** Parametric (v2 §3.6): ping-pong marquee text clipped to the region; y = glyph line-box top. */
    public record ScrollLayer(int x, int y, int w, int h, String text, Color color, PxdFont font,
                              int speedMsPerPx) implements Layer {
    }

    /** Parametric (v2 §3.6): alternates the region's base content ↔ black every periodMs/2. */
    public record BlinkLayer(int x, int y, int w, int h, int periodMs) implements Layer {
    }

    public static PxdDesign parse(final String design) {
        if (design == null || design.isBlank()) {
            throw new IllegalArgumentException("Design is required");
        }
        if (design.getBytes(StandardCharsets.UTF_8).length > MAX_DESIGN_BYTES) {
            throw new IllegalArgumentException("Design exceeds the 512KB limit");
        }

        final JsonNode root;
        try {
            root = LENIENT.readTree(design);
        } catch (final IOException e) {
            throw new IllegalArgumentException("Design is not valid JSON");
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Design must be a JSON object");
        }

        final JsonNode schemaVersion = root.get("schemaVersion");
        if (schemaVersion == null || !schemaVersion.isInt()) {
            throw new IllegalArgumentException("schemaVersion must be 1 or 2");
        }
        if (schemaVersion.asInt() != SCHEMA_VERSION && schemaVersion.asInt() != SCHEMA_VERSION_PARAMETRIC) {
            throw new IllegalArgumentException("Unsupported schemaVersion " + schemaVersion.asInt()
                    + ", expected 1 or 2");
        }
        final boolean parametricAllowed = schemaVersion.asInt() >= SCHEMA_VERSION_PARAMETRIC;

        final JsonNode name = root.get("name");
        if (name == null || !name.isTextual() || name.asText().isEmpty() || name.asText().length() > 64) {
            throw new IllegalArgumentException("name must be 1 to 64 characters");
        }

        final JsonNode frameDelayNode = root.get("frameDelayMs");
        final int frameDelayMs;
        if (frameDelayNode == null) {
            frameDelayMs = DEFAULT_FRAME_DELAY_MS;
        } else if (!frameDelayNode.isInt() || frameDelayNode.asInt() < 10 || frameDelayNode.asInt() > 65535) {
            throw new IllegalArgumentException("frameDelayMs must be an integer between 10 and 65535");
        } else {
            frameDelayMs = frameDelayNode.asInt();
        }

        final JsonNode framesNode = root.get("frames");
        if (framesNode == null || !framesNode.isArray()) {
            throw new IllegalArgumentException("frames must be an array of 1 to 60 frames");
        }
        if (framesNode.size() < 1 || framesNode.size() > MAX_FRAMES) {
            throw new IllegalArgumentException("frames must contain 1 to 60 frames, got " + framesNode.size());
        }

        final List<PxdFrame> frames = new ArrayList<>(framesNode.size());
        for (int i = 0; i < framesNode.size(); i++) {
            frames.add(parseFrame(i, framesNode.get(i), parametricAllowed));
        }

        final PxdDesign parsed = new PxdDesign(name.asText(), frameDelayMs, List.copyOf(frames));
        parsed.validateParametricRules();
        return parsed;
    }

    /** True when any layer is a parametric (§3.6) — such a design compiles to an ACMD batch. */
    public boolean hasParametrics() {
        return parametricCount() > 0;
    }

    /** Number of parametric layers across all frames (a valid parametric design has one frame). */
    public int parametricCount() {
        return (int) frames.stream()
                .flatMap(frame -> frame.layers().stream())
                .filter(layer -> layer instanceof SweepLayer || layer instanceof ScrollLayer
                        || layer instanceof BlinkLayer)
                .count();
    }

    /**
     * Spec §3.4.7: a design carrying any parametric layer must be command-compilable — one
     * frame, ≤ {@value #MAX_PARAMETRICS} parametrics, ≤ {@value #MAX_COMMAND_FONTS} distinct
     * fonts across text+scroll (ACMD FONT pages), every bitmap ≤ {@value #MAX_BLIT_COLORS}
     * distinct RGB565 colors (BLIT palette), and all coordinates 0..{@value #MAX_COORD} (u8).
     */
    private void validateParametricRules() {
        if (!hasParametrics()) {
            return;
        }
        if (frames.size() != 1) {
            throw new IllegalArgumentException(
                    "parametric layers require a single frame, got " + frames.size());
        }
        final int parametrics = parametricCount();
        if (parametrics > MAX_PARAMETRICS) {
            throw new IllegalArgumentException("a design supports at most " + MAX_PARAMETRICS
                    + " parametric layers, got " + parametrics);
        }
        final Set<PxdFont> fonts = new LinkedHashSet<>();
        for (final Layer layer : frames.getFirst().layers()) {
            switch (layer) {
                case TextLayer text -> fonts.add(text.font());
                case ScrollLayer scroll -> fonts.add(scroll.font());
                default -> { }
            }
        }
        if (fonts.size() > MAX_COMMAND_FONTS) {
            throw new IllegalArgumentException("a design with parametric layers supports at most "
                    + MAX_COMMAND_FONTS + " distinct fonts across text and scroll layers, got " + fonts.size());
        }
        for (final Layer layer : frames.getFirst().layers()) {
            if (layer instanceof BitmapLayer bitmap
                    && distinctRgb565Colors(bitmap.image()) > MAX_BLIT_COLORS) {
                throw new IllegalArgumentException("frame 0: bitmap has more than "
                        + MAX_BLIT_COLORS + " distinct RGB565 colors, too many for a parametric design");
            }
            for (final int coord : coordsOf(layer)) {
                if (coord < 0 || coord > MAX_COORD) {
                    throw new IllegalArgumentException(
                            "frame 0: coordinates of a parametric design must be within 0.." + MAX_COORD);
                }
            }
        }
    }

    /** All positional integers of the layer (colors/speeds excluded). */
    private static int[] coordsOf(final Layer layer) {
        return switch (layer) {
            case BitmapLayer ignored -> new int[0];
            case TextLayer text -> new int[]{text.x(), text.y()};
            case RectLayer rect -> new int[]{rect.x(), rect.y(), rect.w(), rect.h()};
            case LineLayer line -> new int[]{line.x1(), line.y1(), line.x2(), line.y2()};
            case CircleLayer circle -> new int[]{circle.cx(), circle.cy(), circle.r()};
            case SweepLayer sweep -> new int[]{sweep.cx(), sweep.cy(), sweep.r()};
            case ScrollLayer scroll -> new int[]{scroll.x(), scroll.y(), scroll.w(), scroll.h()};
            case BlinkLayer blink -> new int[]{blink.x(), blink.y(), blink.w(), blink.h()};
        };
    }

    /** Distinct RGB565 values of the image (the panel's quantized view — BLIT's palette). */
    private static int distinctRgb565Colors(final BufferedImage image) {
        final Set<Integer> colors = new LinkedHashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int argb = image.getRGB(x, y);
                final int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
                colors.add(((r * 31 + 127) / 255) << 11 | ((g * 63 + 127) / 255) << 5 | (b * 31 + 127) / 255);
            }
        }
        return colors.size();
    }

    private static PxdFrame parseFrame(final int frameIdx, final JsonNode frame,
                                       final boolean parametricAllowed) {
        if (frame == null || !frame.isObject()) {
            throw new IllegalArgumentException("frame " + frameIdx + " must be a JSON object");
        }
        final JsonNode layersNode = frame.get("layers");
        if (layersNode != null && !layersNode.isArray()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": layers must be an array");
        }
        final List<Layer> layers = new ArrayList<>(layersNode == null ? 0 : layersNode.size());
        if (layersNode != null) {
            for (final JsonNode layer : layersNode) {
                layers.add(parseLayer(frameIdx, layer, parametricAllowed));
            }
        }
        return new PxdFrame(List.copyOf(layers));
    }

    private static Layer parseLayer(final int frameIdx, final JsonNode layer,
                                    final boolean parametricAllowed) {
        if (layer == null || !layer.isObject()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": layer must be a JSON object");
        }
        final JsonNode type = layer.get("type");
        if (type == null || !type.isTextual()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": layer is missing a type");
        }
        final Layer parsed = switch (type.asText()) {
            case "bitmap" -> bitmap(frameIdx, layer);
            case "text" -> text(frameIdx, layer);
            case "rect" -> rect(frameIdx, layer);
            case "line" -> line(frameIdx, layer);
            case "circle" -> circle(frameIdx, layer);
            case "sweep" -> sweep(frameIdx, layer);
            case "scroll" -> scroll(frameIdx, layer);
            case "blink" -> blink(frameIdx, layer);
            default -> throw new IllegalArgumentException(
                    "frame " + frameIdx + ": unknown layer type '" + type.asText() + "'");
        };
        if (isParametric(parsed) && !parametricAllowed) {
            throw new IllegalArgumentException("frame " + frameIdx + ": layer type '"
                    + type.asText() + "' requires schemaVersion " + SCHEMA_VERSION_PARAMETRIC);
        }
        return parsed;
    }

    private static boolean isParametric(final Layer layer) {
        return layer instanceof SweepLayer || layer instanceof ScrollLayer || layer instanceof BlinkLayer;
    }

    private static Layer bitmap(final int frameIdx, final JsonNode layer) {
        final JsonNode data = layer.get("data");
        if (data == null || !data.isTextual()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": bitmap layer is missing data");
        }
        final String dataUrl = data.asText();
        final BufferedImage image = decodeBitmap(frameIdx, dataUrl);
        if (image.getWidth() != ImageService.W || image.getHeight() != ImageService.H) {
            throw new IllegalArgumentException("frame " + frameIdx + ": bitmap is " + image.getWidth() + "x" + image.getHeight()
                    + ", expected " + ImageService.W + "x" + ImageService.H);
        }
        return new BitmapLayer(image);
    }

    private static BufferedImage decodeBitmap(final int frameIdx, final String dataUrl) {
        final String base64 = dataUrl.contains(",") ? dataUrl.split(",", 2)[1] : dataUrl;
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64);
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("frame " + frameIdx + ": bitmap data is not valid base64");
        }
        try (final ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            final BufferedImage img = ImageIO.read(bis);
            if (img == null) {
                throw new IllegalArgumentException("frame " + frameIdx + ": bitmap data is not a decodable image");
            }
            return img;
        } catch (final IOException e) {
            throw new IllegalArgumentException("frame " + frameIdx + ": bitmap data is not a decodable image");
        }
    }

    private static Layer text(final int frameIdx, final JsonNode layer) {
        final JsonNode textNode = layer.get("text");
        if (textNode == null || !textNode.isTextual() || textNode.asText().isEmpty() || textNode.asText().length() > 255) {
            throw new IllegalArgumentException("frame " + frameIdx + ": text must be 1 to 255 characters");
        }
        final PxdFont font;
        final JsonNode fontNode = layer.get("font");
        if (fontNode == null) {
            font = PxdFont.CG_PIXEL;
        } else if (fontNode.isTextual() && PxdFont.fromId(fontNode.asText()) != null) {
            font = PxdFont.fromId(fontNode.asText());
        } else {
            throw new IllegalArgumentException("frame " + frameIdx + ": unknown font '" + fontNode.asText()
                    + "', must be one of " + List.of(PxdFont.values()));
        }
        return new TextLayer(textNode.asText(),
                requireInt(frameIdx, layer, "x"),
                requireInt(frameIdx, layer, "y"),
                color(frameIdx, layer),
                font);
    }

    private static Layer rect(final int frameIdx, final JsonNode layer) {
        final int w = requireInt(frameIdx, layer, "w");
        final int h = requireInt(frameIdx, layer, "h");
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("frame " + frameIdx + ": rect w and h must be at least 1");
        }
        return new RectLayer(
                requireInt(frameIdx, layer, "x"),
                requireInt(frameIdx, layer, "y"),
                w,
                h,
                color(frameIdx, layer),
                optionalBool(frameIdx, layer, "filled"));
    }

    private static Layer line(final int frameIdx, final JsonNode layer) {
        return new LineLayer(
                requireInt(frameIdx, layer, "x1"),
                requireInt(frameIdx, layer, "y1"),
                requireInt(frameIdx, layer, "x2"),
                requireInt(frameIdx, layer, "y2"),
                color(frameIdx, layer));
    }

    private static Layer circle(final int frameIdx, final JsonNode layer) {
        final int r = requireInt(frameIdx, layer, "r");
        if (r < 0) {
            throw new IllegalArgumentException("frame " + frameIdx + ": circle r must be at least 0");
        }
        return new CircleLayer(
                requireInt(frameIdx, layer, "cx"),
                requireInt(frameIdx, layer, "cy"),
                r,
                color(frameIdx, layer),
                optionalBool(frameIdx, layer, "filled"));
    }

    private static Layer sweep(final int frameIdx, final JsonNode layer) {
        final int r = requireInt(frameIdx, layer, "r");
        if (r < 0) {
            throw new IllegalArgumentException("frame " + frameIdx + ": sweep r must be at least 0");
        }
        final int speed = requireInt(frameIdx, layer, "speedDegPerSec");
        if (speed < 1 || speed > 255) {
            throw new IllegalArgumentException(
                    "frame " + frameIdx + ": sweep speedDegPerSec must be between 1 and 255");
        }
        return new SweepLayer(
                requireInt(frameIdx, layer, "cx"),
                requireInt(frameIdx, layer, "cy"),
                r,
                color(frameIdx, layer),
                speed);
    }

    private static Layer scroll(final int frameIdx, final JsonNode layer) {
        final int w = requireInt(frameIdx, layer, "w");
        final int h = requireInt(frameIdx, layer, "h");
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("frame " + frameIdx + ": scroll w and h must be at least 1");
        }
        final JsonNode textNode = layer.get("text");
        if (textNode == null || !textNode.isTextual() || textNode.asText().isEmpty() || textNode.asText().length() > 255) {
            throw new IllegalArgumentException("frame " + frameIdx + ": scroll text must be 1 to 255 characters");
        }
        final PxdFont font;
        final JsonNode fontNode = layer.get("font");
        if (fontNode == null) {
            font = PxdFont.CG_PIXEL;
        } else if (fontNode.isTextual() && PxdFont.fromId(fontNode.asText()) != null) {
            font = PxdFont.fromId(fontNode.asText());
        } else {
            throw new IllegalArgumentException("frame " + frameIdx + ": unknown font '" + fontNode.asText()
                    + "', must be one of " + List.of(PxdFont.values()));
        }
        final int speed = requireInt(frameIdx, layer, "speedMsPerPx");
        if (speed < 1 || speed > 65535) {
            throw new IllegalArgumentException(
                    "frame " + frameIdx + ": scroll speedMsPerPx must be between 1 and 65535");
        }
        return new ScrollLayer(
                requireInt(frameIdx, layer, "x"),
                requireInt(frameIdx, layer, "y"),
                w,
                h,
                textNode.asText(),
                color(frameIdx, layer),
                font,
                speed);
    }

    private static Layer blink(final int frameIdx, final JsonNode layer) {
        final int w = requireInt(frameIdx, layer, "w");
        final int h = requireInt(frameIdx, layer, "h");
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("frame " + frameIdx + ": blink w and h must be at least 1");
        }
        final int periodMs = requireInt(frameIdx, layer, "periodMs");
        if (periodMs < 2 || periodMs > 65535) {
            throw new IllegalArgumentException(
                    "frame " + frameIdx + ": blink periodMs must be between 2 and 65535");
        }
        return new BlinkLayer(
                requireInt(frameIdx, layer, "x"),
                requireInt(frameIdx, layer, "y"),
                w,
                h,
                periodMs);
    }

    private static int requireInt(final int frameIdx, final JsonNode layer, final String field) {
        final JsonNode node = layer.get(field);
        if (node == null || !node.isInt()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": " + layer.get("type").asText()
                    + " " + field + " must be an integer");
        }
        return node.asInt();
    }

    private static boolean optionalBool(final int frameIdx, final JsonNode layer, final String field) {
        final JsonNode node = layer.get(field);
        if (node == null) {
            return false;
        }
        if (!node.isBoolean()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": " + layer.get("type").asText()
                    + " " + field + " must be true or false");
        }
        return node.asBoolean();
    }

    private static Color color(final int frameIdx, final JsonNode layer) {
        final JsonNode node = layer.get("color");
        if (node == null || !node.isTextual() || !HEX_COLOR.matcher(node.asText()).matches()) {
            throw new IllegalArgumentException("frame " + frameIdx + ": invalid color '"
                    + (node == null ? "missing" : node.asText()) + "', expected #RRGGBB");
        }
        return new Color(Integer.parseInt(node.asText().substring(1), 16));
    }
}
