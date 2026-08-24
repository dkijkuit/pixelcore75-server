package nl.ctasoftware.crypto.ticker.server.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A user-owned custom screen library entry: the `.pxd` design plus its default display
 * duration. Panel rotations reference these by id ({@code CustomScreenConfig.customScreenId});
 * the design is resolved at render time, so library edits propagate to every panel using it.
 */
@Data
@Entity
@Table(name = "px75_custom_screen")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
@AllArgsConstructor
public class Px75CustomScreen {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "custom_screen_id", nullable = false)
    private Long customScreenId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** Denormalized from the design's {@code name} (the .pxd doc is the source of truth). */
    @Column(name = "name", nullable = false, length = 64)
    private String name;

    /** Default slot duration pre-filled when this screen is added to a panel. */
    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    /** The `.pxd` v1 JSON string (validated at save time, same rules as the preview). */
    @Column(name = "design", nullable = false, columnDefinition = "text")
    private String design;

    /** When true, other users can view and reference/copy this screen. */
    @Column(name = "shared", nullable = false)
    private boolean shared;

    /** First rendered frame as a PNG data URL (list preview), regenerated at save time. */
    @Column(name = "thumbnail", columnDefinition = "text")
    private String thumbnail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
