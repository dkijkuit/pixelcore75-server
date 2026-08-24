package nl.ctasoftware.crypto.ticker.server.model.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import nl.ctasoftware.crypto.ticker.server.model.Px75CustomScreen;

import java.time.Instant;

/**
 * A custom screen library entry as seen by the requesting user. List responses omit
 * {@code design}/{@code usageCount}; the detail endpoint includes them.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CustomScreenDto(
        long id,
        String name,
        int durationSeconds,
        boolean shared,
        boolean owned,
        long ownerId,
        String ownerUsername,
        String thumbnail,
        Instant updatedAt,
        String design,
        Long usageCount
) {
    public static CustomScreenDto summary(final Px75CustomScreen entry, final boolean owned, final String ownerUsername) {
        return new CustomScreenDto(entry.getCustomScreenId(), entry.getName(), entry.getDurationSeconds(),
                entry.isShared(), owned, entry.getUserId(), ownerUsername, entry.getThumbnail(),
                entry.getUpdatedAt(), null, null);
    }

    public static CustomScreenDto detail(final Px75CustomScreen entry, final boolean owned, final String ownerUsername,
                                         final long usageCount) {
        return new CustomScreenDto(entry.getCustomScreenId(), entry.getName(), entry.getDurationSeconds(),
                entry.isShared(), owned, entry.getUserId(), ownerUsername, entry.getThumbnail(),
                entry.getUpdatedAt(), entry.getDesign(), usageCount);
    }
}
