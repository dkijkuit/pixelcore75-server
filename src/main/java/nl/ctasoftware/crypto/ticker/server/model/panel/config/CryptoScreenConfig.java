package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record CryptoScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        CryptoConfig config
) implements ScreenConfig {
}
