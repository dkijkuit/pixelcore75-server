package nl.ctasoftware.crypto.ticker.server.model.panel.config;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;

@Data
@Entity
@Table(name = "px75_panel_config")
@NoArgsConstructor
@AllArgsConstructor
public final class Px75PanelConfig {
    @Id
    private long panelId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "screens_config", columnDefinition = "jsonb")
    private List<? extends ScreenConfig> screensConfig;
}

