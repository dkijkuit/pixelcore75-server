package nl.ctasoftware.crypto.ticker.server.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/metadata")
public class MetadataController {
    final SoccerMatchClient soccerMatchClient;

    @GetMapping("soccer/leagues")
    public List<String> getMetadata() {
        return soccerMatchClient.getLeagues().stream().sorted().toList();
    }
}
