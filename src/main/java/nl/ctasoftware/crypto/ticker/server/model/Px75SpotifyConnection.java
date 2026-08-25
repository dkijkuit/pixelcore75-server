package nl.ctasoftware.crypto.ticker.server.model;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A stored Spotify OAuth connection: the refresh token plus the profile it belongs
 * to. One shared connection per server ({@code connectionKey} = "default") — the
 * Now Playing screen renders whatever account was connected last.
 */
@Data
@Entity
@Table(name = "px75_spotify_connection")
@NoArgsConstructor(access = AccessLevel.PROTECTED) // required by JPA
@AllArgsConstructor
public class Px75SpotifyConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "spotify_connection_id", nullable = false)
    private Long spotifyConnectionId;

    /** Logical connection key; the single shared connection uses "default". */
    @Column(name = "connection_key", nullable = false, unique = true, length = 32)
    private String connectionKey;

    @Column(name = "refresh_token", nullable = false, columnDefinition = "text")
    private String refreshToken;

    @Column(name = "spotify_user_id", nullable = false, length = 64)
    private String spotifyUserId;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
