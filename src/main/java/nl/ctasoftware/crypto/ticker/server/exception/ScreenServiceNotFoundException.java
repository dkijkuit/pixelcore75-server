package nl.ctasoftware.crypto.ticker.server.exception;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;

public class ScreenServiceNotFoundException extends RuntimeException {
    public ScreenServiceNotFoundException(ScreenType screenType) {
        super("ScreenService " + screenType + " not found");
    }
}
