package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record CryptoScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        CryptoConfig config,
        boolean disabled
) implements ScreenConfig {

    public CryptoScreenConfig(final ScreenType screenType, final int durationSeconds,
                              final CryptoConfig config) {
        this(screenType, durationSeconds, config, false);
    }
}
