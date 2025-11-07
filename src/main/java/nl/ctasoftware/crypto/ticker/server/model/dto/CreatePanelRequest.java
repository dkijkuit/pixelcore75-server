package nl.ctasoftware.crypto.ticker.server.model.dto;

public record CreatePanelRequest(
        long userId,
        String username,
        String clientMac,
        String serial,
        String name,
        String panelType
) {
}
