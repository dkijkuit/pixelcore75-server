package nl.ctasoftware.crypto.ticker.server.service.command;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ACMD v1 batch framing: byte-exact header/command encoding, encode&rarr;parse roundtrips
 * for every opcode, and the forward-compatibility rules (payload commands self-delimiting
 * and skippable on semantic violations; unknown opcodes ending the parsed prefix).
 */
class CommandBatchTests {

    private static final int RED = 0xF800;
    private static final int GREEN = 0x07E0;

    private static List<AcmdCommand.Glyph> handGlyphs() {
        // A: 2x2, bitmap rows 10 / 01; B: 1x1; C: 1x1 with negative xOff and +1 yOff
        return List.of(
                new AcmdCommand.Glyph('A', 2, 2, 3, 0, 0, new byte[]{(byte) 0x80, (byte) 0x40}),
                new AcmdCommand.Glyph('B', 1, 1, 2, 0, 0, new byte[]{(byte) 0x80}),
                new AcmdCommand.Glyph('C', 1, 1, 1, -1, 1, new byte[]{(byte) 0x80}));
    }

    @Test
    void headerAndSimpleCommandsAreByteExact() {
        final byte[] framed = CommandBatch.builder().cls(RED).pix(1, 2, GREEN).build();
        assertArrayEquals(new byte[]{
                0x41, 0x43, 0x4D, 0x44, // "ACMD"
                0x01,                   // version 1
                0x02, 0x00,             // cmdCount u16 LE
                0x01, 0x00, (byte) 0xF8,       // CLS color 0xF800 LE
                0x02, 0x01, 0x02, (byte) 0xE0, 0x07 // PIX x=1 y=2 color 0x07E0 LE
        }, framed);
    }

    @Test
    void sweepAndBlinkAreByteExact() {
        final byte[] framed = CommandBatch.builder().sweep(10, 11, 5, RED, 90).blink(1, 2, 3, 4, 1000).build();
        assertArrayEquals(new byte[]{
                0x41, 0x43, 0x4D, 0x44, 0x01, 0x02, 0x00,
                0x20, 10, 11, 5, 0x00, (byte) 0xF8, 90,          // SWEEP cx cy r color speed
                0x22, 1, 2, 3, 4, (byte) 0xE8, 0x03              // BLINK x y w h periodMs=1000 LE
        }, framed);
    }

    @Test
    void textCommandCarriesFixedArgsOutsidePayloadLen() {
        final byte[] framed = CommandBatch.builder().text(0, 5, 7, GREEN, "AB").build();
        assertArrayEquals(new byte[]{
                0x41, 0x43, 0x4D, 0x44, 0x01, 0x01, 0x00,
                0x11, 0x03, 0x00,             // TEXT, payloadLen = 1 len byte + "AB"
                0x00, 0x05, 0x07,             // fontId, x, y
                (byte) 0xE0, 0x07,            // color LE
                0x02, 'A', 'B'                // payload
        }, framed);
    }

    @Test
    void everyOpcodeRoundtripsThroughParse() {
        final byte[] framed = CommandBatch.builder()
                .nop()
                .cls(RED)
                .pix(1, 2, GREEN)
                .line(0, 0, 10, 8, GREEN)
                .rect(2, 3, 4, 5, GREEN)
                .fill(6, 7, 8, 9, GREEN)
                .circ(10, 11, 5, GREEN)
                .blit(20, 21, 2, 2, new int[]{RED, GREEN, RED, RED})
                .fontPage(0, handGlyphs())
                .text(0, 5, 7, GREEN, "AB")
                .sweep(30, 16, 5, GREEN, 90)
                .scroll(0, 24, 64, 8, 0, GREEN, 10, "AB")
                .blink(1, 2, 3, 4, 1000)
                .build();

        final AcmdParser.Parsed parsed = AcmdParser.parse(framed);
        assertFalse(parsed.truncated());
        final List<AcmdCommand> cmds = parsed.commands();
        assertEquals(13, cmds.size());

        assertInstanceOf(AcmdCommand.Nop.class, cmds.get(0));
        assertEquals(new AcmdCommand.Cls(RED), cmds.get(1));
        assertEquals(new AcmdCommand.Pix(1, 2, GREEN), cmds.get(2));
        assertEquals(new AcmdCommand.Line(0, 0, 10, 8, GREEN), cmds.get(3));
        assertEquals(new AcmdCommand.Rect(2, 3, 4, 5, GREEN), cmds.get(4));
        assertEquals(new AcmdCommand.Fill(6, 7, 8, 9, GREEN), cmds.get(5));
        assertEquals(new AcmdCommand.Circ(10, 11, 5, GREEN), cmds.get(6));

        final AcmdCommand.Blit blit = assertInstanceOf(AcmdCommand.Blit.class, cmds.get(7));
        assertEquals(20, blit.x());
        assertEquals(21, blit.y());
        assertEquals(2, blit.w());
        assertEquals(2, blit.h());
        assertArrayEquals(new int[]{RED, GREEN, RED, RED}, blit.pixels());

        final AcmdCommand.FontPage font = assertInstanceOf(AcmdCommand.FontPage.class, cmds.get(8));
        assertEquals(0, font.pageId());
        assertEquals(3, font.glyphs().size());
        assertEquals('A', font.glyphs().get(0).code());

        assertEquals(new AcmdCommand.Text(0, 5, 7, GREEN, "AB"), cmds.get(9));
        assertEquals(new AcmdCommand.Sweep(30, 16, 5, GREEN, 90), cmds.get(10));
        assertEquals(new AcmdCommand.Scroll(0, 24, 64, 8, 0, GREEN, 10, "AB"), cmds.get(11));
        assertEquals(new AcmdCommand.Blink(1, 2, 3, 4, 1000), cmds.get(12));
    }

    @Test
    void cmdCountBoundsParsingAndTrailingBytesAreIgnored() {
        final byte[] framed = CommandBatch.builder().cls(RED).pix(1, 2, GREEN).build();
        // header claims 1 command: the PIX bytes are trailing garbage, not a command
        final byte[] oneCommand = framed.clone();
        oneCommand[5] = 0x01;
        oneCommand[6] = 0x00;

        final AcmdParser.Parsed parsed = AcmdParser.parse(oneCommand);

        assertFalse(parsed.truncated());
        assertEquals(List.of(new AcmdCommand.Cls(RED)), parsed.commands());
    }

    @Test
    void wrongMagicOrVersionYieldsEmptyPrefix() {
        final byte[] framed = CommandBatch.builder().cls(RED).build();
        framed[0] = 'X';
        assertTrue(AcmdParser.parse(framed).truncated());
        assertTrue(AcmdParser.parse(framed).commands().isEmpty());

        final byte[] badVersion = CommandBatch.builder().cls(RED).build();
        badVersion[4] = 0x02;
        assertTrue(AcmdParser.parse(badVersion).truncated());
        assertTrue(AcmdParser.parse(badVersion).commands().isEmpty());
    }

    @Test
    void unknownOpcodeEndsParsedPrefix() {
        // count=3, cmd1 CLS RED, cmd2 unknown opcode 0x77, cmd3 CLS black (never reached)
        final byte[] batch = new byte[7 + 3 + 1 + 3];
        batch[0] = 'A'; batch[1] = 'C'; batch[2] = 'M'; batch[3] = 'D';
        batch[4] = 0x01;
        batch[5] = 0x03; batch[6] = 0x00;
        batch[7] = 0x01; batch[8] = 0x00; batch[9] = (byte) 0xF8; // CLS RED
        batch[10] = 0x77;                                          // unknown opcode
        batch[11] = 0x01; batch[12] = 0x00; batch[13] = 0x00;     // CLS black, never reached

        final AcmdParser.Parsed parsed = AcmdParser.parse(batch);

        assertTrue(parsed.truncated());
        assertEquals(List.of(new AcmdCommand.Cls(RED)), parsed.commands());
        assertEquals(RED, AcmdMirror.parse(batch).baseFrame()[0], "parsed prefix renders");
    }

    @Test
    void semanticallyInvalidPayloadCommandIsSkippedNotFatal() {
        // TEXT whose payload length byte disagrees with the actual payload is skipped;
        // the PIX after it still parses and renders
        final byte[] batch = new byte[7 + 1 + 2 + 5 + 3 + 5];
        int p = 0;
        batch[p++] = 'A'; batch[p++] = 'C'; batch[p++] = 'M'; batch[p++] = 'D';
        batch[p++] = 0x01;
        batch[p++] = 0x02; batch[p++] = 0x00;
        batch[p++] = AcmdOpcode.TEXT;
        batch[p++] = 0x03; batch[p++] = 0x00; // payloadLen 3
        batch[p++] = 0x00; batch[p++] = 0x05; batch[p++] = 0x07; // fontId x y
        batch[p++] = 0x00; batch[p++] = 0x00; // color
        batch[p++] = 0x03; // len claims 3...
        batch[p++] = 'A';  // ...but only 2 payload bytes follow
        batch[p++] = 0x00;
        batch[p++] = AcmdOpcode.PIX;
        batch[p++] = 0x01; batch[p++] = 0x02;
        batch[p++] = 0x00; batch[p++] = 0x00; // black pixel at (1,2)

        final AcmdParser.Parsed parsed = AcmdParser.parse(batch);

        assertFalse(parsed.truncated());
        assertEquals(1, parsed.commands().size());
        assertInstanceOf(AcmdCommand.Pix.class, parsed.commands().get(0));
    }

    @Test
    void encoderRejectsOutOfRangeArguments() {
        final CommandBatch b = CommandBatch.builder();
        assertThrows(IllegalArgumentException.class, () -> b.cls(0x10000));
        assertThrows(IllegalArgumentException.class, () -> b.cls(-1));
        assertThrows(IllegalArgumentException.class, () -> b.pix(256, 0, RED));
        assertThrows(IllegalArgumentException.class, () -> b.pix(0, -1, RED));
        assertThrows(IllegalArgumentException.class, () -> b.rect(0, 0, 0, 5, RED));
        assertThrows(IllegalArgumentException.class, () -> b.fill(0, 0, 5, 0, RED));
        assertThrows(IllegalArgumentException.class, () -> b.circ(0, 0, -1, RED));
        assertThrows(IllegalArgumentException.class, () -> b.sweep(0, 0, 5, RED, 0), "speed must be >= 1");
        assertThrows(IllegalArgumentException.class, () -> b.blink(0, 0, 5, 5, 1), "period must be >= 2");
        assertThrows(IllegalArgumentException.class, () -> b.text(4, 0, 0, RED, "A"), "fontId 0..3");
        assertThrows(IllegalArgumentException.class, () -> b.text(0, 0, 0, RED, ""), "len 1..255");
        assertThrows(IllegalArgumentException.class, () -> b.text(0, 0, 0, RED, "é"), "ASCII 32..126");
        assertThrows(IllegalArgumentException.class, () -> b.scroll(0, 0, 64, 8, 0, RED, 0, "A"), "speed >= 1");
        assertThrows(IllegalArgumentException.class, () -> b.fontPage(4, handGlyphs()), "pageId 0..3");
        assertThrows(IllegalArgumentException.class,
                () -> b.blit(0, 0, 2, 2, new int[]{RED, GREEN, RED}), "pixels must be w*h");
    }

    @Test
    void emptyBatchIsHeaderOnly() {
        final byte[] framed = CommandBatch.builder().build();
        assertArrayEquals(new byte[]{0x41, 0x43, 0x4D, 0x44, 0x01, 0x00, 0x00}, framed);
        assertFalse(AcmdParser.parse(framed).truncated());
        assertEquals(0, AcmdParser.parse(framed).commands().size());
    }
}
