package nl.ctasoftware.crypto.ticker.server.service.panel;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class Px75PanelService {
    final PanelRepository panelRepository;
    final PanelConfigRepository panelConfigRepository;

    public List<Px75Panel> getPx75Panels() {
        return panelRepository.findAll();
    }

    public List<Px75Panel> getPx75PanelsForUser(Px75User user) {
        return panelRepository.findByUserId(user.getId());
    }

    public Px75Panel getPx75Panel(final long panelId) {
        return panelRepository.findById(panelId).orElseThrow(() -> new EntityNotFoundException("Px75Panel with id " + panelId + " not found"));
    }

    public Px75Panel getPx75PanelForUser(final long userId, final long panelId) {
        return panelRepository.findByUserIdAndPanelId(userId, panelId).orElseThrow(() -> new EntityNotFoundException("Px75Panel with id " + panelId + " not found for user: " + userId));
    }

    public Px75Panel addPx75Panel(final Px75Panel px75Panel) {
        px75Panel.setSerial(px75Panel.getSerial().toUpperCase());

        final Px75Panel savedPanel = panelRepository.save(px75Panel);
        panelConfigRepository.save(new Px75PanelConfig(savedPanel.getPanelId(), Collections.emptyList()));
        return savedPanel;
    }

    public String getSerialForUser(long userId, long panelId) {
        return panelRepository.findSerialByIdAndUserId(panelId, userId)
                .orElseThrow(() -> new org.springframework.security.access.AccessDeniedException("No access"));
    }

    public void deletePanel(long panelId, Px75User requester) {
        var panel = panelRepository.findById(panelId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Panel not found"));

        boolean isAdmin = requester.getRoles().contains(Px75Role.ADMIN);
        if (!isAdmin && !Objects.equals(panel.getUserId(), requester.getId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not allowed to delete this panel");
        }

        panelRepository.deleteById(panelId);
        panelConfigRepository.deleteById(panelId);
    }


    public String getSerial(long panelId) {
        return panelRepository.findSerialById(panelId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Panel not found"));
    }

    public Px75Panel updatePx75Panel(final Px75Panel px75Panel) {
        return panelRepository.save(px75Panel);
    }
}
