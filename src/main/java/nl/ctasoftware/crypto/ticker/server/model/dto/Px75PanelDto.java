package nl.ctasoftware.crypto.ticker.server.model.dto;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;

public record Px75PanelDto(
        long panelId,
        long userId,
        String username,
        String clientMac,
        String serial,
        String name,
        String panelType,
        Px75PanelConfig config
) {
    public static Px75PanelDto from(final Px75Panel px75Panel, final Px75PanelConfig panelConfig, final String username) {
        return new Px75PanelDto(px75Panel.getPanelId(), px75Panel.getUserId(), username, px75Panel.getClientMac(), px75Panel.getSerial(), px75Panel.getName(), px75Panel.getPanelType().name(), panelConfig);
    }

    public static Px75PanelDto from(final Px75Panel px75Panel, final String username) {
        return new Px75PanelDto(px75Panel.getPanelId(), px75Panel.getUserId(), username, px75Panel.getClientMac(), px75Panel.getSerial(), px75Panel.getName(), px75Panel.getPanelType().name(), null);
    }
}

