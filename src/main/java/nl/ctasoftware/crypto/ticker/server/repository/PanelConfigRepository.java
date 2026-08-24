package nl.ctasoftware.crypto.ticker.server.repository;

import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PanelConfigRepository extends JpaRepository<Px75PanelConfig, Long> {
    Optional<Px75PanelConfig> findById(Long panelId);

    /**
     * Panel configs whose rotation jsonb contains the given containment pattern, e.g.
     * {@code [{"customScreenId":5}]} — jsonb containment matches any rotation entry that
     * carries that key/value regardless of the other properties.
     */
    @Query(value = "select count(*) from px75_panel_config where screens_config @> cast(:ref as jsonb)",
            nativeQuery = true)
    long countContaining(@Param("ref") String ref);
}
