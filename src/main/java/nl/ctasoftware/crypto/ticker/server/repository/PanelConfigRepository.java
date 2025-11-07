package nl.ctasoftware.crypto.ticker.server.repository;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PanelConfigRepository extends JpaRepository<Px75PanelConfig, Long> {
    Optional<Px75PanelConfig> findById(Long panelId);
}
