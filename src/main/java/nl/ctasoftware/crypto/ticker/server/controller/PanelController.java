package nl.ctasoftware.crypto.ticker.server.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.Px75Panel;
import nl.ctasoftware.crypto.ticker.server.model.Px75PanelType;
import nl.ctasoftware.crypto.ticker.server.model.Px75Role;
import nl.ctasoftware.crypto.ticker.server.model.Px75User;
import nl.ctasoftware.crypto.ticker.server.model.dto.CreatePanelRequest;
import nl.ctasoftware.crypto.ticker.server.model.dto.Px75PanelDto;
import nl.ctasoftware.crypto.ticker.server.model.dto.UpdatePanelDetailsRequest;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.Px75PanelConfig;
import nl.ctasoftware.crypto.ticker.server.security.SseTicketService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageBroadcasterService;
import nl.ctasoftware.crypto.ticker.server.service.job.Px75PanelJobScheduler;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelConfigService;
import nl.ctasoftware.crypto.ticker.server.service.panel.Px75PanelService;
import nl.ctasoftware.crypto.ticker.server.service.user.Px75UserDetailsService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/panel")
public class PanelController {
    final Px75UserDetailsService userDetailsService;
    final Px75PanelService px75PanelService;
    final Px75PanelConfigService px75PanelConfigService;
    final ImageBroadcasterService imageBroadcasterService;
    final Px75PanelJobScheduler px75PanelJobScheduler;
    final SseTicketService sseTicketService;

    @PostMapping("register")
    Px75PanelDto register(@AuthenticationPrincipal Px75User user, @RequestBody CreatePanelRequest dto) {
        long userId = dto.userId();
        if (!user.getRoles().contains(Px75Role.ADMIN)) {
            userId = user.getId();
        }
        var created = px75PanelService.addPx75Panel(new Px75Panel(null, userId, dto.serial(), dto.clientMac(), dto.name(),
                Px75PanelType.valueOf(dto.panelType())));

        px75PanelJobScheduler.schedulePanelScreenJob(created.getPanelId(), created.getUserId());

        return Px75PanelDto.from(created, user.getUsername());
    }

    @GetMapping("{panelId}")
    Px75PanelDto getPx75Panel(@AuthenticationPrincipal Px75User userDetails, @PathVariable final long panelId) {
        final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(panelId);

        if (userDetails.getRoles().contains(Px75Role.ADMIN)) {
            final Px75Panel px75Panel = px75PanelService.getPx75Panel(panelId);
            final Px75User px75UserById = userDetailsService.getPx75UserById(px75Panel.getUserId());
            return Px75PanelDto.from(px75Panel, panelConfig, px75UserById.getUsername());
        } else {
            return Px75PanelDto.from(px75PanelService.getPx75PanelForUser(userDetails.getId(), panelId), panelConfig, userDetails.getUsername());
        }
    }

    @GetMapping
    List<Px75PanelDto> getPanels(@AuthenticationPrincipal Px75User userDetails) {
        if (userDetails.getRoles().contains(Px75Role.ADMIN)) {
            return px75PanelService.getPx75Panels().stream().map(px75Panel -> {
                final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(px75Panel.getPanelId());
                final Px75User px75UserById = userDetailsService.getPx75UserById(px75Panel.getUserId());
                return Px75PanelDto.from(px75Panel, panelConfig, px75UserById.getUsername());
            }).toList();
        } else {
            return px75PanelService.getPx75PanelsForUser(userDetails).stream()
                    .map(px75Panel -> {
                        final Px75PanelConfig panelConfig = px75PanelConfigService.getPanelConfig(px75Panel.getPanelId());
                        return Px75PanelDto.from(px75Panel, panelConfig, userDetails.getUsername());
                    }).toList();
        }
    }

    @PostMapping("config")
    Px75PanelConfig savePanelConfig(@AuthenticationPrincipal Px75User userDetails, @RequestBody Px75PanelConfig panelConfig) {
        Px75Panel px75Panel;
        if (!userDetails.getRoles().contains(Px75Role.ADMIN)) {
            px75Panel = px75PanelService.getPx75PanelForUser(userDetails.getId(), panelConfig.getPanelId());
        } else {
            px75Panel = px75PanelService.getPx75Panel(panelConfig.getPanelId());
        }

        log.info("Saving panel config for panel {}", px75Panel.getSerial());

        final Px75PanelConfig px75PanelConfig = px75PanelConfigService.save(panelConfig);
        px75PanelJobScheduler.schedulePanelScreenJob(px75Panel.getPanelId(), px75Panel.getUserId());

        return px75PanelConfig;
    }

    @GetMapping(path = "image/{panelId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter registerImageUpdates(@AuthenticationPrincipal Px75User userDetails, @PathVariable final long panelId) {
        Px75Panel panel = userDetails.getRoles().contains(Px75Role.ADMIN)
                ? px75PanelService.getPx75Panel(panelId)
                : px75PanelService.getPx75PanelForUser(userDetails.getId(), panelId);

        // Service creates & manages the emitter (heartbeats + cleanup)
        return imageBroadcasterService.register(panel.getSerial());
    }

    @PostMapping("image/{panelId}/ticket")
    public Map<String, Object> issueSseTicket(@AuthenticationPrincipal Px75User user,
                                              @PathVariable long panelId) {
        // Reuse your ownership/admin check
        final Px75Panel panel = user.getRoles().contains(Px75Role.ADMIN)
                ? px75PanelService.getPx75Panel(panelId)
                : px75PanelService.getPx75PanelForUser(user.getId(), panelId);

        long ttlSeconds = 120; // 2 minutes is typical
        String token = sseTicketService.issue(user.getId(), panel.getPanelId(), ttlSeconds);
        return Map.of("ticket", token, "expiresIn", ttlSeconds);
    }

    @DeleteMapping("/{panelId}")
    public ResponseEntity<Void> delete(@PathVariable long panelId,
                                       @AuthenticationPrincipal Px75User user) {
        // If non-admins should only delete their own panels, enforce here:
        px75PanelService.deletePanel(panelId, user);
        return ResponseEntity.noContent().build(); // 204
    }

    @PatchMapping("/{panelId}")
    public ResponseEntity<Px75Panel> updatePanel(
            @PathVariable long panelId,
            @AuthenticationPrincipal Px75User user,
            @RequestBody UpdatePanelDetailsRequest updatePanelDetailsRequest
    ) {
        final Px75Panel existingPanel = user.getRoles().contains(Px75Role.ADMIN)
                ? px75PanelService.getPx75Panel(panelId)
                : px75PanelService.getPx75PanelForUser(user.getId(), panelId);

        existingPanel.setName(updatePanelDetailsRequest.name());
        existingPanel.setPanelType(updatePanelDetailsRequest.panelType());
        existingPanel.setSerial(updatePanelDetailsRequest.serial());
        existingPanel.setClientMac(updatePanelDetailsRequest.clientMac());

        return ResponseEntity.ok(px75PanelService.updatePx75Panel(existingPanel));
    }
}
