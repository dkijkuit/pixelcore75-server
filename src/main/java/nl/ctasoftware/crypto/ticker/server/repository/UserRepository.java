package nl.ctasoftware.crypto.ticker.server.repository;

import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserRepository extends JpaRepository<Px75User, Long> {
    Optional<Px75User> findByUsername(String username);
    Optional<Px75User> findByEmail(String email);
    boolean existsByUsername(String username);
    boolean existsByEmail(String email);
}
