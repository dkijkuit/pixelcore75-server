package nl.ctasoftware.crypto.ticker.server.service.panel;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.job.PanelRotationControl;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

@Slf4j
@Service
@RequiredArgsConstructor
public class Px75PanelService {
    /**
     * The serial doubles as the MQTT topic base ({@code <serial>}, {@code <serial>/cmd},
     * {@code <serial>/anim/...}) — anything outside {@code [A-Z0-9_-]} could inject topic
     * separators/wildcards or escape file paths. Enforced on create and update; the entity
     * uppercases on the way in, so lowercase input is accepted.
     */
    static final Pattern SERIAL_PATTERN = Pattern.compile("^[A-Z0-9_-]+$");

    final PanelRepository panelRepository;
    final PanelConfigRepository panelConfigRepository;
    final PanelRotationControl panelRotationControl;

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
        validateSerial(px75Panel.getSerial(), null);

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

        // Stop de screen-job vóór de rijen weggaan: zonder dit blijft de job de (inmiddels
        // verwijderde) config oneindig publiceren op het serial-topic van het paneel.
        panelRotationControl.stopBeforeDelete(panel.getSerial());

        panelRepository.deleteById(panelId);
        panelConfigRepository.deleteById(panelId);
    }


    public String getSerial(long panelId) {
        return panelRepository.findSerialById(panelId)
                .orElseThrow(() -> new jakarta.persistence.EntityNotFoundException("Panel not found"));
    }

    public Px75Panel updatePx75Panel(final Px75Panel px75Panel) {
        validateSerial(px75Panel.getSerial(), px75Panel.getPanelId());
        return panelRepository.save(px75Panel);
    }

    private void validateSerial(final String serial, final Long currentPanelId) {
        if (serial == null || !SERIAL_PATTERN.matcher(serial).matches()) {
            throw new IllegalArgumentException(
                    "Panel serial is required and may only contain A-Z, 0-9, '_' and '-'");
        }
        panelRepository.findBySerialIgnoreCase(serial)
                .filter(panel -> currentPanelId == null || !currentPanelId.equals(panel.getPanelId()))
                .ifPresent(panel -> {
                    throw new IllegalArgumentException("Panel serial already in use: " + serial);
                });
    }
}
