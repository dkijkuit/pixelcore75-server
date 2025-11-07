package nl.ctasoftware.crypto.ticker.server.service.panel;

import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ImageScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
@RequiredArgsConstructor
public class Px75PanelConfigService {
    final PanelConfigRepository panelConfigRepository;
    final ImageService imageService;

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
