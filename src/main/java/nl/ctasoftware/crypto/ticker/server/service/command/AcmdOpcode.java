package nl.ctasoftware.crypto.ticker.server.service.command;

/**
 * ACMD v1 opcode table. Two wire forms exist (AGENTS.md protocol section): <b>fixed</b>
 * commands are {@code opcode + fixed args} with a per-opcode arg length; <b>payload</b>
 * commands are {@code opcode + payloadLen u16 LE + fixed args + payload}, where payloadLen
 * counts only the trailing payload bytes (e.g. BLIT's x/y/w/h sit between the length field
 * and the RLE payload). The table is what makes the forward-compatibility rules work:
 * a payload-form opcode is always skippable (its extent is
 * {@code fixedArgs + 2 + payloadLen}), while an opcode outside the table has an unknowable
 * length and ends the parsed prefix.
 */
public final class AcmdOpcode {

    public static final int NOP = 0x00;
    public static final int CLS = 0x01;
    public static final int PIX = 0x02;
    public static final int LINE = 0x03;
    public static final int RECT = 0x04;
    public static final int FILL = 0x05;
    public static final int CIRC = 0x06;
    public static final int BLIT = 0x07;
    public static final int FONT = 0x10;
    public static final int TEXT = 0x11;
    public static final int SWEEP = 0x20;
    public static final int SCROLL = 0x21;
    public static final int BLINK = 0x22;

    public static final int VERSION = 1;
    public static final int TEXT_MAX_LEN = 255;
    public static final int FONT_PAGES = 4;
    public static final int GLYPH_MAX_DIM = 32;
    public static final int UNKNOWN_GLYPH_ADVANCE = 4;

    private AcmdOpcode() {
    }

    /** True when the opcode uses the {@code opcode + payloadLen u16 LE + ...} form. */
    public static boolean isPayloadForm(final int opcode) {
        return opcode == BLIT || opcode == FONT || opcode == TEXT || opcode == SCROLL;
    }

    /**
     * Fixed-arg byte count for the opcode (payload-form opcodes: the bytes between the u16
     * payloadLen and the payload), or -1 when the opcode is not in the v1 table.
     */
    public static int fixedArgsOf(final int opcode) {
        return switch (opcode) {
            case NOP -> 0;
            case CLS -> 2;                     // color u16
            case PIX -> 4;                     // x, y u8 + color u16
            case LINE -> 6;                    // x0, y0, x1, y1 u8 + color u16
            case RECT, FILL -> 6;              // x, y, w, h u8 + color u16
            case CIRC -> 5;                    // cx, cy, r u8 + color u16
            case SWEEP -> 6;                   // cx, cy, r u8 + color u16 + speed u8
            case BLINK -> 6;                   // x, y, w, h u8 + periodMs u16
            case BLIT -> 4;                    // x, y, w, h u8 (payload: 32 B palette + runs)
            case FONT -> 0;                    // payload: pageId + glyphCount + glyphs
            case TEXT -> 5;                    // fontId, x, y u8 + color u16 (payload: len + ASCII)
            case SCROLL -> 9;                   // x, y, w, h, fontId u8 + color u16 + speed u16 (payload: len + ASCII)
            default -> -1;
        };
    }
}
