package nl.ctasoftware.crypto.ticker.server.service.panel;

import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class Px75PanelConfigService {
    final PanelConfigRepository panelConfigRepository;
    final ImageService imageService;

    /**
     * Persists the rotation. CUSTOM screens (inline legacy designs and library references
     * alike) are validated before this by {@code CustomScreenLibraryService.validateRotation},
     * invoked from {@code PanelController.savePanelConfig} where the requesting user is known.
     * IMAGE screens are validated here: a bad base64 payload used to save fine and then
     * fail at render on every cycle (silent failure); this turns it into a 400 at save.
     */
    public Px75PanelConfig save(final Px75PanelConfig panelConfig) {
        panelConfig.getScreensConfig().stream()
                .filter(screenConfig -> screenConfig.screenType() == ScreenType.IMAGE)
                .map(ImageScreenConfig.class::cast)
                .forEach(imageService::validate);

        return panelConfigRepository.save(panelConfig);
    }

    public Px75PanelConfig getPanelConfig(final long id) {
        return panelConfigRepository.findById(id).orElse(null);
    }

    /** Batch fetch for the panel listing (one IN query instead of one per panel). */
    public Map<Long, Px75PanelConfig> getConfigs(final Collection<Long> panelIds) {
        if (panelIds.isEmpty()) {
            return Map.of();
        }
        return panelConfigRepository.findAllById(panelIds).stream()
                .collect(Collectors.toMap(Px75PanelConfig::getPanelId, Function.identity()));
    }
}
