package nl.ctasoftware.crypto.ticker.server.service.panel;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.job.PanelRotationControl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Px75PanelServiceTests {

    @Mock PanelRepository panelRepository;
    @Mock PanelConfigRepository panelConfigRepository;
    @Mock PanelRotationControl panelRotationControl;

    @InjectMocks Px75PanelService px75PanelService;

    @Test
    void deletePanelStopsTheScreenJobBeforeDeleting() {
        var panel = new Px75Panel(1L, 42L, "PANEL-1", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);
        when(panelRepository.findById(1L)).thenReturn(Optional.of(panel));
        var owner = new Px75User("owner", "pw", "owner@example.com", Set.of(Px75Role.USER));
        owner.setId(42L);

        px75PanelService.deletePanel(1L, owner);

        // Ordering is the point: the rotation must be dead before the rows go, or it keeps
        // publishing the deleted config on the serial topic.
        final InOrder inOrder = inOrder(panelRotationControl, panelRepository, panelConfigRepository);
        inOrder.verify(panelRotationControl).stopBeforeDelete("PANEL-1");
        inOrder.verify(panelRepository).deleteById(1L);
        inOrder.verify(panelConfigRepository).deleteById(1L);
    }

    @Test
    void deletePanelForbiddenForNonOwnerDoesNotStopJobOrDelete() {
        var panel = new Px75Panel(1L, 42L, "PANEL-1", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);
        when(panelRepository.findById(1L)).thenReturn(Optional.of(panel));
        var stranger = new Px75User("stranger", "pw", "stranger@example.com", Set.of(Px75Role.USER));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> px75PanelService.deletePanel(1L, stranger));

        verify(panelRotationControl, never()).stopBeforeDelete(org.mockito.ArgumentMatchers.anyString());
        verify(panelRepository, never()).deleteById(anyLong());
    }

    /* ------- serial validation (review 2026-08-27: the serial is an MQTT topic base) ------- */

    @Test
    void addPanelRejectsInvalidSerialCharacters() {
        var panel = new Px75Panel(null, 42L, "BAD/SERIAL;", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> px75PanelService.addPx75Panel(panel));

        verify(panelRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addPanelRejectsNullSerialInsteadOfThrowingNpe() {
        var panel = new Px75Panel(null, 42L, null, "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> px75PanelService.addPx75Panel(panel));

        verify(panelRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void addPanelAcceptsLowercaseAndNormalizes() {
        var panel = new Px75Panel(null, 42L, "abc-123_x", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);
        when(panelRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> {
            final Px75Panel toSave = inv.getArgument(0);
            toSave.setPanelId(9L); // what IDENTITY generation does
            return toSave;
        });
        when(panelConfigRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        var saved = px75PanelService.addPx75Panel(panel);

        org.junit.jupiter.api.Assertions.assertEquals("ABC-123_X", saved.getSerial());
    }

    @Test
    void addPanelRejectsDuplicateSerial() {
        when(panelRepository.findBySerialIgnoreCase("PANEL-1"))
                .thenReturn(Optional.of(new Px75Panel(7L, 1L, "PANEL-1", "00:00:00:00:00:00", "other",
                        Px75PanelType.P_64_X_32)));
        var panel = new Px75Panel(null, 42L, "PANEL-1", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> px75PanelService.addPx75Panel(panel));

        verify(panelRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updatePanelRejectsSerialOwnedByAnotherPanel() {
        when(panelRepository.findBySerialIgnoreCase("OTHER-PANEL"))
                .thenReturn(Optional.of(new Px75Panel(7L, 1L, "OTHER-PANEL", "00:00:00:00:00:00", "other",
                        Px75PanelType.P_64_X_32)));
        var panel = new Px75Panel(1L, 42L, "OTHER-PANEL", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> px75PanelService.updatePx75Panel(panel));

        verify(panelRepository, never()).save(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void updatePanelAllowsKeepingItsOwnSerial() {
        when(panelRepository.findBySerialIgnoreCase("PANEL-1"))
                .thenReturn(Optional.of(new Px75Panel(1L, 42L, "PANEL-1", "00:00:00:00:00:00", "test",
                        Px75PanelType.P_64_X_32)));
        when(panelRepository.save(org.mockito.ArgumentMatchers.any())).thenAnswer(inv -> inv.getArgument(0));
        var panel = new Px75Panel(1L, 42L, "PANEL-1", "00:00:00:00:00:00", "renamed", Px75PanelType.P_64_X_32);

        px75PanelService.updatePx75Panel(panel);

        verify(panelRepository).save(panel);
    }
}
