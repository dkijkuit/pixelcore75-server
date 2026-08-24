package nl.ctasoftware.crypto.ticker.server.model.dto;

import java.util.List;

public record CustomScreenPreviewResponse(
        List<String> frames,
        int frameDelayMs
) {
}
