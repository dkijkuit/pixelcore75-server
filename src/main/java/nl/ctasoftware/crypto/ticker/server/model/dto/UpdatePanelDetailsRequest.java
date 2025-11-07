package nl.ctasoftware.crypto.ticker.server.model.dto;

import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;

public record UpdatePanelDetailsRequest(
        String clientMac,
        String serial,
        String name,
        Px75PanelType panelType
) {
}
