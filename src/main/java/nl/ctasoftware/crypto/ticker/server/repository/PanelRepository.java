package nl.ctasoftware.crypto.ticker.server.repository;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PanelRepository extends JpaRepository<Px75Panel, Long> {
    List<Px75Panel> findByUserId(Long userId);

    Optional<Px75Panel> findByUserIdAndPanelId(Long userId, Long panelId);

    @Query("select serial from Px75Panel where panelId = :panelId and userId = :userId")
    Optional<String> findSerialByIdAndUserId(@Param("panelId") long panelId, @Param("userId") long userId);

    @Query("select p.serial from Px75Panel p where p.panelId = :panelId")
    Optional<String> findSerialById(@Param("panelId") long panelId);
}
