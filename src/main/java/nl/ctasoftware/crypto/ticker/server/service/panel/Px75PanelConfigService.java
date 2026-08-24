package nl.ctasoftware.crypto.ticker.server.service.panel;

import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class Px75PanelConfigService {
    final PanelConfigRepository panelConfigRepository;
    final ImageService imageService;

    /**
     * Persists the rotation. CUSTOM screens (inline legacy designs and library references
     * alike) are validated before this by {@code CustomScreenLibraryService.validateRotation},
     * invoked from {@code PanelController.savePanelConfig} where the requesting user is known.
     */
    public Px75PanelConfig save(final Px75PanelConfig panelConfig) {
        panelConfig.getScreensConfig().stream()
                .filter(screenConfig -> screenConfig.screenType() == ScreenType.IMAGE)
                .forEach(screenConfig -> {
                    ImageScreenConfig imageScreenConfig = (ImageScreenConfig) screenConfig;

                });

        return panelConfigRepository.save(panelConfig);
    }

    public Px75PanelConfig getPanelConfig(final long id) {
        return panelConfigRepository.findById(id).orElse(null);
    }
}
