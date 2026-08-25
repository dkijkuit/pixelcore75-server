package nl.ctasoftware.crypto.ticker.server.model.panel.config;

public record ImageScreenConfig(
        ScreenType screenType,
        int durationSeconds,
        String image,
        String imageUploadData,
        boolean disabled
) implements ScreenConfig {

    public ImageScreenConfig(final ScreenType screenType, final int durationSeconds,
                             final String image, final String imageUploadData) {
        this(screenType, durationSeconds, image, imageUploadData, false);
    }
}
