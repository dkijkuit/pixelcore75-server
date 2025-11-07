package nl.ctasoftware.crypto.ticker.server.font;

import java.awt.*;

public class FontRenderer {
    private final byte[] bitmaps;
    private final GFXGlyph[] glyphs;
    private final int firstChar;

    public FontRenderer(byte[] bitmaps, GFXGlyph[] glyphs, int firstChar) {
        this.bitmaps = bitmaps;
        this.glyphs = glyphs;
        this.firstChar = firstChar;
    }

    public void drawText(Graphics g, String text, int x, int y, Color color) {
        int cursorX = x;
        for (char c : text.toCharArray()) {
            if (c < firstChar || c >= firstChar + glyphs.length) continue;
            drawChar(g, c, cursorX, y, color);
            cursorX += glyphs[c - firstChar].xAdvance;
        }
    }

    private void drawChar(Graphics g, char c, int x, int y, Color color) {
        GFXGlyph glyph = glyphs[c - firstChar];
        int byteOffset = glyph.bitmapOffset;
        int w = glyph.width;
        int h = glyph.height;

        int bitIndex = 0;

        for (int yy = 0; yy < h; yy++) {
            for (int xx = 0; xx < w; xx++) {
                int byteIndex = byteOffset + (bitIndex / 8);
                int bit = 7 - (bitIndex % 8);
                if ((bitmaps[byteIndex] & (1 << bit)) != 0) {
                    g.setColor(color);
                    g.fillRect(x + glyph.xOffset + xx, y + glyph.yOffset + yy, 1, 1);
                }
                bitIndex++;
            }
        }
    }
}
