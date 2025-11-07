package nl.ctasoftware.crypto.ticker.server.model;

import jakarta.persistence.*;
import lombok.*;

import java.util.Objects;

@Data
@Entity
@Table(name = "px75_panel")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
@AllArgsConstructor
public class Px75Panel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "panel_id", nullable = false)
    private Long panelId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Setter(AccessLevel.NONE)
    @Column(name = "serial", nullable = false)
    private String serial;

    @Column(name = "client_mac", nullable = false, length = 17)
    private String clientMac;

    @Column(name = "name", nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "panel_type", nullable = false)
    private Px75PanelType panelType;

    public void setSerial(String serial) {
        this.serial = Objects.nonNull(serial) ? serial.toUpperCase() : null;
    }

    public String getSerial(){
        return Objects.nonNull(serial) ? serial.toUpperCase() : null;
    }
}

