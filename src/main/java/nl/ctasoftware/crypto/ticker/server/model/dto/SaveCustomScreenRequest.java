package nl.ctasoftware.crypto.ticker.server.model.dto;

/** Create/update body for a custom screen library entry. The design's name is the screen's name. */
public record SaveCustomScreenRequest(
        String design,
        int durationSeconds,
        boolean shared
) {
}
