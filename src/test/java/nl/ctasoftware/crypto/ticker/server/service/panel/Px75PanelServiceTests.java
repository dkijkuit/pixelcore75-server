package nl.ctasoftware.crypto.ticker.server.service.panel;

import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.repository.PanelConfigRepository;
import nl.ctasoftware.crypto.ticker.server.repository.PanelRepository;
import nl.ctasoftware.crypto.ticker.server.service.job.JobSchedulerService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class Px75PanelServiceTests {

    @Mock PanelRepository panelRepository;
    @Mock PanelConfigRepository panelConfigRepository;
    @Mock JobSchedulerService jobSchedulerService;

    @InjectMocks Px75PanelService px75PanelService;

    @Test
    void deletePanelStopsTheScreenJobBeforeDeleting() {
        var panel = new Px75Panel(1L, 42L, "PANEL-1", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);
        when(panelRepository.findById(1L)).thenReturn(Optional.of(panel));
        var owner = new Px75User("owner", "pw", "owner@example.com", Set.of(Px75Role.USER));
        owner.setId(42L);

        px75PanelService.deletePanel(1L, owner);

        verify(jobSchedulerService).stop("PANEL-1", true);
        verify(panelRepository).deleteById(1L);
        verify(panelConfigRepository).deleteById(1L);
    }

    @Test
    void deletePanelForbiddenForNonOwnerDoesNotStopJobOrDelete() {
        var panel = new Px75Panel(1L, 42L, "PANEL-1", "00:00:00:00:00:00", "test", Px75PanelType.P_64_X_32);
        when(panelRepository.findById(1L)).thenReturn(Optional.of(panel));
        var stranger = new Px75User("stranger", "pw", "stranger@example.com", Set.of(Px75Role.USER));

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.web.server.ResponseStatusException.class,
                () -> px75PanelService.deletePanel(1L, stranger));

        verify(jobSchedulerService, never()).stop(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyBoolean());
        verify(panelRepository, never()).deleteById(anyLong());
    }
}
