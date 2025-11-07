package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record ImageScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String image,
        String imageUploadData
) implements ScreenConfig {}
