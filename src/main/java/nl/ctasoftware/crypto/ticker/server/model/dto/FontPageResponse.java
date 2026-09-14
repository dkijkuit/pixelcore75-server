package nl.ctasoftware.crypto.ticker.server.model.dto;

import java.util.List;

/**
 * ACMD FONT-page glyphs of one pxd font (spec §3.3), as served to the designer's edit
 * canvas: the exact bitmaps the panel draws, so canvas text is pixel-identical to the
 * rendered screen. {@code ascent} is the frame path's AWT ascent and {@code lineTop} the
 * page's line-box normalization (see {@code FontPageExtractor}); a client converts a pxd
 * line-box-top y to the ACMD glyph-top reference with {@code y + ascent + lineTop}.
 */
public record FontPageResponse(
        String font,
        int ascent,
        int lineTop,
        List<GlyphResponse> glyphs
) {

    /**
     * One glyph: {@code bitmap} is base64 of {@code h} rows of {@code ceil(w/8)} bytes,
     * MSB-first — the same wire bytes the ACMD FONT command carries. Unknown characters
     * advance 4 px and draw nothing (the panel's TEXT rule).
     */
    public record GlyphResponse(
            int code,
            int w,
            int h,
            int xAdvance,
            int xOff,
            int yOff,
            String bitmap
    ) {
    }
}
