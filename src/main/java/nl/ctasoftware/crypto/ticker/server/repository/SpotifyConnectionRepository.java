package nl.ctasoftware.crypto.ticker.server.repository;

import nl.ctasoftware.crypto.ticker.server.model.Px75SpotifyConnection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SpotifyConnectionRepository extends JpaRepository<Px75SpotifyConnection, Long> {
    Optional<Px75SpotifyConnection> findByConnectionKey(String connectionKey);
}
