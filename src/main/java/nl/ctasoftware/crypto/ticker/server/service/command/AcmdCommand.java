package nl.ctasoftware.crypto.ticker.server.service.command;

import java.util.List;

/**
 * One parsed ACMD v1 command (see the AGENTS.md protocol section and {@link AcmdOpcode} for
 * the byte-exact wire shapes). Records carry decoded values; {@link Blit#pixels} holds the
 * expanded w&times;h RGB565 block and {@link Glyph#bitmap} the MSB-first packed glyph rows,
 * so the mirror and tests never re-derive wire layout.
 */
public sealed interface AcmdCommand {

    record Nop() implements AcmdCommand {}

    record Cls(int color) implements AcmdCommand {}

    record Pix(int x, int y, int color) implements AcmdCommand {}

    record Line(int x0, int y0, int x1, int y1, int color) implements AcmdCommand {}

    record Rect(int x, int y, int w, int h, int color) implements AcmdCommand {}

    record Fill(int x, int y, int w, int h, int color) implements AcmdCommand {}

    record Circ(int cx, int cy, int r, int color) implements AcmdCommand {}

    /** Decoded BLIT: {@code pixels} is the expanded w&times;h row-major RGB565 block. */
    record Blit(int x, int y, int w, int h, int[] pixels) implements AcmdCommand {}

    /**
     * One FONT-page glyph. {@code bitmap} holds {@code h} rows of {@code ceil(w / 8)}
     * bytes, MSB-first within each row. {@code xAdvance}/{@code xOff}/{@code yOff} are
     * signed bytes; yOff is relative to the glyph line-box top (see FontPageExtractor).
     */
    record Glyph(int code, int w, int h, int xAdvance, int xOff, int yOff, byte[] bitmap) {}

    record FontPage(int pageId, List<Glyph> glyphs) implements AcmdCommand {}

    record Text(int fontId, int x, int y, int color, String ascii) implements AcmdCommand {}

    record Sweep(int cx, int cy, int r, int color, int speedDegPerSec) implements AcmdCommand {}

    record Scroll(int x, int y, int w, int h, int fontId, int color, int speedMsPerPx, String ascii)
            implements AcmdCommand {}

    record Blink(int x, int y, int w, int h, int periodMs) implements AcmdCommand {}
}
