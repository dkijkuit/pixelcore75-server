package nl.ctasoftware.crypto.ticker.server.font;

public class GFXGlyph {
    public final int bitmapOffset;
    public final int width;
    public final int height;
    public final int xAdvance;
    public final int xOffset;
    public final int yOffset;

    public GFXGlyph(int bitmapOffset, int width, int height, int xAdvance, int xOffset, int yOffset) {
        this.bitmapOffset = bitmapOffset;
        this.width = width;
        this.height = height;
        this.xAdvance = xAdvance;
        this.xOffset = xOffset;
        this.yOffset = yOffset;
    }
}
