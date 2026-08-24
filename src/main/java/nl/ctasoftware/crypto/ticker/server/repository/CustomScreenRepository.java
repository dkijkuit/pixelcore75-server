package nl.ctasoftware.crypto.ticker.server.repository;

import nl.ctasoftware.crypto.ticker.server.model.Px75CustomScreen;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomScreenRepository extends JpaRepository<Px75CustomScreen, Long> {
    List<Px75CustomScreen> findByUserIdOrderByUpdatedAtDesc(long userId);

    List<Px75CustomScreen> findBySharedTrueAndUserIdNotOrderByUpdatedAtDesc(long userId);
}
