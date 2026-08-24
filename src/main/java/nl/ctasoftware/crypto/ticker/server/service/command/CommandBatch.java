package nl.ctasoftware.crypto.ticker.server.service.command;

import nl.ctasoftware.crypto.ticker.server.service.job.AnimationFrameCodec;

import java.awt.Font;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.List;

/**
 * Fluent encoder for ACMD v1 command batches (plan §6 / AGENTS.md protocol section).
 * Each builder method validates its arguments against the wire format's u8/u16/i8 bounds
 * and appends the command's bytes; {@link #build()} frames the result:
 * {@code "ACMD" + version u8 (=1) + cmdCount u16 LE + commands}. Payload-form commands
 * (BLIT/FONT/TEXT/SCROLL) carry {@code opcode + payloadLen u16 LE + fixed args + payload}
 * where payloadLen counts only the payload bytes. BLIT bodies reuse the phase-2 PAL_RLE
 * codec ({@link AnimationFrameCodec#encodePalRleBody}) generalized to w-wide rows.
 */
public final class CommandBatch {

    private final ByteArrayOutputStream commands = new ByteArrayOutputStream();
    private int cmdCount;

    private CommandBatch() {
    }

    public static CommandBatch builder() {
        return new CommandBatch();
    }

    public CommandBatch nop() {
        commands.write(AcmdOpcode.NOP);
        cmdCount++;
        return this;
    }

    public CommandBatch cls(final int color) {
        requireColor(color);
        commands.write(AcmdOpcode.CLS);
        writeU16(color);
        cmdCount++;
        return this;
    }

    public CommandBatch pix(final int x, final int y, final int color) {
        requireCoord(x, "x");
        requireCoord(y, "y");
        requireColor(color);
        commands.write(AcmdOpcode.PIX);
        commands.write(x);
        commands.write(y);
        writeU16(color);
        cmdCount++;
        return this;
    }

    public CommandBatch line(final int x0, final int y0, final int x1, final int y1, final int color) {
        requireCoord(x0, "x0");
        requireCoord(y0, "y0");
        requireCoord(x1, "x1");
        requireCoord(y1, "y1");
        requireColor(color);
        commands.write(AcmdOpcode.LINE);
        commands.write(x0);
        commands.write(y0);
        commands.write(x1);
        commands.write(y1);
        writeU16(color);
        cmdCount++;
        return this;
    }

    public CommandBatch rect(final int x, final int y, final int w, final int h, final int color) {
        return outlineRect(AcmdOpcode.RECT, x, y, w, h, color);
    }

    public CommandBatch fill(final int x, final int y, final int w, final int h, final int color) {
        return outlineRect(AcmdOpcode.FILL, x, y, w, h, color);
    }

    private CommandBatch outlineRect(final int opcode, final int x, final int y,
                                     final int w, final int h, final int color) {
        requireCoord(x, "x");
        requireCoord(y, "y");
        requirePositiveDim(w, "w");
        requirePositiveDim(h, "h");
        requireColor(color);
        commands.write(opcode);
        commands.write(x);
        commands.write(y);
        commands.write(w);
        commands.write(h);
        writeU16(color);
        cmdCount++;
        return this;
    }

    public CommandBatch circ(final int cx, final int cy, final int r, final int color) {
        requireCoord(cx, "cx");
        requireCoord(cy, "cy");
        if (r < 0 || r > 255) {
            throw new IllegalArgumentException("radius " + r + " outside u8 range");
        }
        requireColor(color);
        commands.write(AcmdOpcode.CIRC);
        commands.write(cx);
        commands.write(cy);
        commands.write(r);
        writeU16(color);
        cmdCount++;
        return this;
    }

    /**
     * BLIT: {@code pixels} is the w&times;h row-major RGB565 block (at most 16 distinct
     * colors — the PAL_RLE payload has a 4-bit palette index).
     */
    public CommandBatch blit(final int x, final int y, final int w, final int h, final int[] pixels) {
        requireCoord(x, "x");
        requireCoord(y, "y");
        requirePositiveDim(w, "w");
        requirePositiveDim(h, "h");
        if (pixels == null || pixels.length != w * h) {
            throw new IllegalArgumentException("pixels must hold exactly w*h = " + w * h + " entries");
        }
        final byte[] body = AnimationFrameCodec.encodePalRleBody(pixels, w);
        commands.write(AcmdOpcode.BLIT);
        writeU16(body.length);
        commands.write(x);
        commands.write(y);
        commands.write(w);
        commands.write(h);
        commands.writeBytes(body);
        cmdCount++;
        return this;
    }

    /** FONT page extracted from a TTF (same files/sizes as the {@code PaintConfig} beans). */
    public CommandBatch fontPage(final int pageId, final File ttf, final float size) {
        return fontPagePayload(pageId, FontPageExtractor.payload(pageId, FontPageExtractor.loadFont(ttf, size)));
    }

    public CommandBatch fontPage(final int pageId, final Font font) {
        return fontPagePayload(pageId, FontPageExtractor.payload(pageId, font));
    }

    /** FONT page from already-extracted glyphs (tests, programmatic pages). */
    public CommandBatch fontPage(final int pageId, final List<AcmdCommand.Glyph> glyphs) {
        FontPageExtractor.requirePageId(pageId);
        if (glyphs == null || glyphs.isEmpty() || glyphs.size() > 255) {
            throw new IllegalArgumentException("glyph count must be 1..255");
        }
        int size = 2;
        for (final AcmdCommand.Glyph g : glyphs) {
            size += 6 + g.bitmap().length;
        }
        final byte[] payload = new byte[size];
        int out = 0;
        payload[out++] = (byte) pageId;
        payload[out++] = (byte) glyphs.size();
        for (final AcmdCommand.Glyph g : glyphs) {
            payload[out++] = (byte) g.code();
            payload[out++] = (byte) g.w();
            payload[out++] = (byte) g.h();
            payload[out++] = (byte) g.xAdvance();
            payload[out++] = (byte) g.xOff();
            payload[out++] = (byte) g.yOff();
            System.arraycopy(g.bitmap(), 0, payload, out, g.bitmap().length);
            out += g.bitmap().length;
        }
        return fontPagePayload(pageId, payload);
    }

    /** Emits an already-built FONT payload (package-private: extraction roundtrip tests). */
    CommandBatch fontPagePayload(final int pageId, final byte[] payload) {
        FontPageExtractor.requirePageId(pageId);
        commands.write(AcmdOpcode.FONT);
        writeU16(payload.length);
        commands.writeBytes(payload);
        cmdCount++;
        return this;
    }

    /** TEXT: glyphs referenced by {@code fontId} must have been uploaded by an earlier FONT. */
    public CommandBatch text(final int fontId, final int x, final int y, final int color, final String ascii) {
        requireFontId(fontId);
        requireCoord(x, "x");
        requireCoord(y, "y");
        requireColor(color);
        requireAscii(ascii);
        final byte[] bytes = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        commands.write(AcmdOpcode.TEXT);
        writeU16(bytes.length + 1);
        commands.write(fontId);
        commands.write(x);
        commands.write(y);
        writeU16(color);
        commands.write(bytes.length);
        commands.writeBytes(bytes);
        cmdCount++;
        return this;
    }

    /** SWEEP: {@code speedDegPerSec} 1..255; the only parametric radar-style primitive. */
    public CommandBatch sweep(final int cx, final int cy, final int r, final int color, final int speedDegPerSec) {
        requireCoord(cx, "cx");
        requireCoord(cy, "cy");
        if (r < 0 || r > 255) {
            throw new IllegalArgumentException("radius " + r + " outside u8 range");
        }
        requireColor(color);
        if (speedDegPerSec < 1 || speedDegPerSec > 255) {
            throw new IllegalArgumentException("speedDegPerSec " + speedDegPerSec + " outside 1..255");
        }
        commands.write(AcmdOpcode.SWEEP);
        commands.write(cx);
        commands.write(cy);
        commands.write(r);
        writeU16(color);
        commands.write(speedDegPerSec);
        cmdCount++;
        return this;
    }

    public CommandBatch scroll(final int x, final int y, final int w, final int h, final int fontId,
                               final int color, final int speedMsPerPx, final String ascii) {
        requireCoord(x, "x");
        requireCoord(y, "y");
        requirePositiveDim(w, "w");
        requirePositiveDim(h, "h");
        requireFontId(fontId);
        requireColor(color);
        if (speedMsPerPx < 1 || speedMsPerPx > 0xFFFF) {
            throw new IllegalArgumentException("speedMsPerPx " + speedMsPerPx + " outside 1..65535");
        }
        requireAscii(ascii);
        final byte[] bytes = ascii.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        commands.write(AcmdOpcode.SCROLL);
        writeU16(bytes.length + 1);
        commands.write(x);
        commands.write(y);
        commands.write(w);
        commands.write(h);
        commands.write(fontId);
        writeU16(color);
        commands.write(speedMsPerPx & 0xFF);
        commands.write((speedMsPerPx >> 8) & 0xFF);
        commands.write(bytes.length);
        commands.writeBytes(bytes);
        cmdCount++;
        return this;
    }

    /** BLINK: {@code periodMs} u16, at least 2 so periodMs/2 is at least 1 ms. */
    public CommandBatch blink(final int x, final int y, final int w, final int h, final int periodMs) {
        requireCoord(x, "x");
        requireCoord(y, "y");
        requirePositiveDim(w, "w");
        requirePositiveDim(h, "h");
        if (periodMs < 2 || periodMs > 0xFFFF) {
            throw new IllegalArgumentException("periodMs " + periodMs + " outside 2..65535");
        }
        commands.write(AcmdOpcode.BLINK);
        commands.write(x);
        commands.write(y);
        commands.write(w);
        commands.write(h);
        commands.write(periodMs & 0xFF);
        commands.write((periodMs >> 8) & 0xFF);
        cmdCount++;
        return this;
    }

    /** Frames the batch: {@code "ACMD" + version u8 + cmdCount u16 LE + commands}. */
    public byte[] build() {
        final byte[] body = commands.toByteArray();
        final byte[] framed = new byte[7 + body.length];
        framed[0] = 'A';
        framed[1] = 'C';
        framed[2] = 'M';
        framed[3] = 'D';
        framed[4] = (byte) AcmdOpcode.VERSION;
        framed[5] = (byte) cmdCount;
        framed[6] = (byte) (cmdCount >> 8);
        System.arraycopy(body, 0, framed, 7, body.length);
        return framed;
    }

    private void writeU16(final int v) {
        commands.write(v & 0xFF);
        commands.write((v >> 8) & 0xFF);
    }

    private static void requireCoord(final int v, final String name) {
        if (v < 0 || v > 255) {
            throw new IllegalArgumentException(name + " " + v + " outside u8 range");
        }
    }

    private static void requirePositiveDim(final int v, final String name) {
        if (v < 1 || v > 255) {
            throw new IllegalArgumentException(name + " " + v + " outside 1..255");
        }
    }

    private static void requireColor(final int v) {
        if (v < 0 || v > 0xFFFF) {
            throw new IllegalArgumentException("color " + v + " outside RGB565 u16 range");
        }
    }

    private static void requireFontId(final int v) {
        if (v < 0 || v >= AcmdOpcode.FONT_PAGES) {
            throw new IllegalArgumentException("fontId " + v + " outside 0.." + (AcmdOpcode.FONT_PAGES - 1));
        }
    }

    private static void requireAscii(final String s) {
        if (s == null || s.isEmpty() || s.length() > AcmdOpcode.TEXT_MAX_LEN) {
            throw new IllegalArgumentException("text length must be 1.." + AcmdOpcode.TEXT_MAX_LEN);
        }
        for (final char c : s.toCharArray()) {
            if (c < 32 || c > 126) {
                throw new IllegalArgumentException("character '" + c + "' outside ASCII 32..126");
            }
        }
    }
}
