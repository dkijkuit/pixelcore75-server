package nl.ctasoftware.crypto.ticker.server.service.screen.custom;

/**
 * The .pxd v1 font id registry (spec §3.3). Ids map 1:1 onto the {@code PaintConfig}
 * font beans; {@code CustomScreenService} holds that mapping (AWT fonts are Spring beans,
 * the parser itself stays context-free so configs can parse without one).
 */
public enum PxdFont {
    CG_PIXEL,
    MINI_LINE,
    HABBO,
    LED_BOARD,
    TINY,
    FROST,
    GRINCHED;

    public static PxdFont fromId(final String id) {
        for (final PxdFont font : values()) {
            if (font.name().equals(id)) {
                return font;
            }
        }
        return null;
    }
}
