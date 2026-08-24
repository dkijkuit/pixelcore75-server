package nl.ctasoftware.crypto.ticker.server.service.command;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses a framed ACMD v1 batch ({@code "ACMD" + version u8 + cmdCount u16 LE + commands})
 * into {@link AcmdCommand}s, implementing the firmware's forward-compatibility rules:
 * <ul>
 *   <li>A <b>payload-form</b> command (BLIT/FONT/TEXT/SCROLL) is self-delimiting via its
 *       fixed-arg length (opcode table) + u16 payloadLen, so any semantic violation inside
 *       it (bad runs, bad pageId, truncated text) only skips that command — parsing and
 *       rendering continue with the next one.</li>
 *   <li>An opcode <b>outside the v1 table</b> has an unknowable length, so it ends the
 *       parsed prefix: everything before it renders, everything after is ignored
 *       ({@code truncated} = true).</li>
 *   <li>Structural truncation (frame cut short mid-command) also ends the parsed prefix.</li>
 * </ul>
 */
public final class AcmdParser {

    /** {@code truncated} = true when the frame ended before cmdCount commands parsed cleanly. */
    public record Parsed(List<AcmdCommand> commands, boolean truncated) {
        static Parsed clean(final List<AcmdCommand> commands) {
            return new Parsed(commands, false);
        }
    }

    private AcmdParser() {
    }

    public static Parsed parse(final byte[] framed) {
        final List<AcmdCommand> commands = new ArrayList<>();
        if (framed == null || framed.length < 7
                || framed[0] != 'A' || framed[1] != 'C' || framed[2] != 'M' || framed[3] != 'D'
                || (framed[4] & 0xFF) != AcmdOpcode.VERSION) {
            return new Parsed(commands, true);
        }
        final int cmdCount = (framed[5] & 0xFF) | (framed[6] & 0xFF) << 8;
        int pos = 7;

        for (int i = 0; i < cmdCount; i++) {
            if (pos >= framed.length) {
                return new Parsed(commands, true);
            }
            final int opcode = framed[pos] & 0xFF;
            final int fixedArgs = AcmdOpcode.fixedArgsOf(opcode);
            if (fixedArgs < 0) {
                return new Parsed(commands, true); // unknown opcode: length unknowable
            }
            pos++;
            if (AcmdOpcode.isPayloadForm(opcode)) {
                if (pos + 2 > framed.length) {
                    return new Parsed(commands, true);
                }
                final int payloadLen = (framed[pos] & 0xFF) | (framed[pos + 1] & 0xFF) << 8;
                pos += 2;
                if (pos + fixedArgs + payloadLen > framed.length) {
                    return new Parsed(commands, true);
                }
                final byte[] fixed = slice(framed, pos, fixedArgs);
                final byte[] payload = slice(framed, pos + fixedArgs, payloadLen);
                pos += fixedArgs + payloadLen;
                final AcmdCommand cmd = decodePayloadCommand(opcode, fixed, payload);
                if (cmd != null) {
                    commands.add(cmd);
                } // else: semantic violation -> command skipped, batch continues
            } else {
                if (pos + fixedArgs > framed.length) {
                    return new Parsed(commands, true);
                }
                commands.add(decodeFixedCommand(opcode, framed, pos));
                pos += fixedArgs;
            }
        }
        return Parsed.clean(commands);
    }

    private static byte[] slice(final byte[] src, final int off, final int len) {
        final byte[] out = new byte[len];
        System.arraycopy(src, off, out, 0, len);
        return out;
    }

    private static AcmdCommand decodeFixedCommand(final int opcode, final byte[] f, final int pos) {
        return switch (opcode) {
            case AcmdOpcode.NOP -> new AcmdCommand.Nop();
            case AcmdOpcode.CLS -> new AcmdCommand.Cls(u16(f, pos));
            case AcmdOpcode.PIX -> new AcmdCommand.Pix(f[pos] & 0xFF, f[pos + 1] & 0xFF, u16(f, pos + 2));
            case AcmdOpcode.LINE -> new AcmdCommand.Line(f[pos] & 0xFF, f[pos + 1] & 0xFF,
                    f[pos + 2] & 0xFF, f[pos + 3] & 0xFF, u16(f, pos + 4));
            case AcmdOpcode.RECT -> new AcmdCommand.Rect(f[pos] & 0xFF, f[pos + 1] & 0xFF,
                    f[pos + 2] & 0xFF, f[pos + 3] & 0xFF, u16(f, pos + 4));
            case AcmdOpcode.FILL -> new AcmdCommand.Fill(f[pos] & 0xFF, f[pos + 1] & 0xFF,
                    f[pos + 2] & 0xFF, f[pos + 3] & 0xFF, u16(f, pos + 4));
            case AcmdOpcode.CIRC -> new AcmdCommand.Circ(f[pos] & 0xFF, f[pos + 1] & 0xFF,
                    f[pos + 2] & 0xFF, u16(f, pos + 3));
            case AcmdOpcode.SWEEP -> new AcmdCommand.Sweep(f[pos] & 0xFF, f[pos + 1] & 0xFF,
                    f[pos + 2] & 0xFF, u16(f, pos + 3), f[pos + 5] & 0xFF);
            case AcmdOpcode.BLINK -> new AcmdCommand.Blink(f[pos] & 0xFF, f[pos + 1] & 0xFF,
                    f[pos + 2] & 0xFF, f[pos + 3] & 0xFF, u16(f, pos + 4));
            default -> throw new IllegalStateException("opcode " + opcode + " not a fixed command");
        };
    }

    /** Returns null when the payload-form command violates the spec and must be skipped. */
    private static AcmdCommand decodePayloadCommand(final int opcode, final byte[] fixed, final byte[] payload) {
        try {
            return switch (opcode) {
                case AcmdOpcode.BLIT -> decodeBlit(fixed, payload);
                case AcmdOpcode.FONT -> decodeFont(payload);
                case AcmdOpcode.TEXT -> decodeText(fixed, payload);
                case AcmdOpcode.SCROLL -> decodeScroll(fixed, payload);
                default -> throw new IllegalStateException("opcode " + opcode + " not a payload command");
            };
        } catch (final IllegalArgumentException e) {
            return null; // semantic violation: self-delimiting skip
        }
    }

    private static AcmdCommand decodeBlit(final byte[] fixed, final byte[] payload) {
        final int x = fixed[0] & 0xFF;
        final int y = fixed[1] & 0xFF;
        final int w = fixed[2] & 0xFF;
        final int h = fixed[3] & 0xFF;
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("blit w/h must be >= 1");
        }
        final int[] pixels = nl.ctasoftware.crypto.ticker.server.service.job.AnimationFrameCodec
                .decodePalRleBody(payload, w, w * h);
        return new AcmdCommand.Blit(x, y, w, h, pixels);
    }

    private static AcmdCommand decodeFont(final byte[] payload) {
        if (payload.length < 2) {
            throw new IllegalArgumentException("font payload too short");
        }
        final int pageId = payload[0] & 0xFF;
        if (pageId >= AcmdOpcode.FONT_PAGES) {
            throw new IllegalArgumentException("pageId " + pageId + " outside 0..3");
        }
        final int glyphCount = payload[1] & 0xFF;
        int pos = 2;
        final List<AcmdCommand.Glyph> glyphs = new ArrayList<>(glyphCount);
        for (int i = 0; i < glyphCount; i++) {
            if (pos + 6 > payload.length) {
                break; // malformed glyph: keep the valid prefix of the page
            }
            final int code = payload[pos] & 0xFF;
            final int w = payload[pos + 1] & 0xFF;
            final int h = payload[pos + 2] & 0xFF;
            final int bitmapLen = h * ((w + 7) >> 3);
            if (w < 1 || w > AcmdOpcode.GLYPH_MAX_DIM || h < 1 || h > AcmdOpcode.GLYPH_MAX_DIM
                    || pos + 6 + bitmapLen > payload.length) {
                break;
            }
            final byte[] bitmap = slice(payload, pos + 6, bitmapLen);
            glyphs.add(new AcmdCommand.Glyph(code, w, h, payload[pos + 3], payload[pos + 4], payload[pos + 5],
                    bitmap));
            pos += 6 + bitmapLen;
        }
        if (glyphs.isEmpty()) {
            throw new IllegalArgumentException("font page carries no valid glyph");
        }
        return new AcmdCommand.FontPage(pageId, glyphs);
    }

    private static AcmdCommand decodeText(final byte[] fixed, final byte[] payload) {
        final int fontId = fixed[0] & 0xFF;
        final int x = fixed[1] & 0xFF;
        final int y = fixed[2] & 0xFF;
        final int color = u16(fixed, 3);
        final String ascii = decodeAscii(payload);
        if (fontId >= AcmdOpcode.FONT_PAGES) {
            throw new IllegalArgumentException("fontId " + fontId + " outside 0..3");
        }
        return new AcmdCommand.Text(fontId, x, y, color, ascii);
    }

    private static AcmdCommand decodeScroll(final byte[] fixed, final byte[] payload) {
        final int x = fixed[0] & 0xFF;
        final int y = fixed[1] & 0xFF;
        final int w = fixed[2] & 0xFF;
        final int h = fixed[3] & 0xFF;
        final int fontId = fixed[4] & 0xFF;
        final int color = u16(fixed, 5);
        final int speedMsPerPx = u16(fixed, 7);
        final String ascii = decodeAscii(payload);
        if (w < 1 || h < 1) {
            throw new IllegalArgumentException("scroll w/h must be >= 1");
        }
        if (fontId >= AcmdOpcode.FONT_PAGES) {
            throw new IllegalArgumentException("fontId " + fontId + " outside 0..3");
        }
        if (speedMsPerPx < 1) {
            throw new IllegalArgumentException("speedMsPerPx must be >= 1");
        }
        return new AcmdCommand.Scroll(x, y, w, h, fontId, color, speedMsPerPx, ascii);
    }

    /** payload must be exactly {@code len u8 (1..255) + that many ASCII bytes}. */
    private static String decodeAscii(final byte[] payload) {
        if (payload.length < 1) {
            throw new IllegalArgumentException("empty text payload");
        }
        final int len = payload[0] & 0xFF;
        if (len < 1 || payload.length != 1 + len) {
            throw new IllegalArgumentException("text payload length mismatch");
        }
        return new String(payload, 1, len, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    private static int u16(final byte[] b, final int pos) {
        return (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8;
    }
}
