package nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.espn;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.exception.Px75ClientException;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.NextMatchNotFoundException;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatch;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerTeam;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import nl.ctasoftware.crypto.ticker.server.utils.RandomUserAgent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.CacheConfig;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@ConditionalOnProperty(name = "pixelcore75.soccer.client", havingValue = "espn")
@CacheConfig(cacheManager = "soccerCacheManager", cacheNames = "soccerMatch")
public class ESPNSoccerMatchClient implements SoccerMatchClient {
    final RestClient espnSiteRestClient;
    final RestClient espnCoreApiRestClient;
    final String espnCoreApiBasePath;

    public ESPNSoccerMatchClient(final RestClient espnSiteRestClient, final RestClient espnCoreApiRestClient, @Value("${pixelcore75.soccer.basePath}") final String espnCoreApiBasePath) {
        this.espnSiteRestClient = espnSiteRestClient;
        this.espnCoreApiRestClient = espnCoreApiRestClient;
        this.espnCoreApiBasePath = espnCoreApiBasePath;
    }

    @Override
    @Cacheable(
            key = "#competition + ':' + #teamId",
            unless = "#result != null && #result.started() && !#result.finished()"
    )
    public Optional<SoccerMatch> getSoccerMatch(String competition, String teamId) {
        log.info("ESPN update soccer match for {}, {}", competition, teamId);
        try{
            return Optional.of(getMatch(competition, teamId));
        } catch(NextMatchNotFoundException nextMatchNotFoundException){
            return Optional.empty();
        }
    }

    @Override
    @Cacheable
    public List<String> getLeagues() {
        final ResponseEntity<PagedRefs> response = espnCoreApiRestClient.get()
                .uri(uriBuilder -> uriBuilder.path("/soccer/leagues").queryParam("limit", 500).build())
                .header("User-Agent", RandomUserAgent.getRandomUserAgent())
                .retrieve()
                .toEntity(PagedRefs.class);

        if (response.getBody() == null || response.getBody().items() == null || response.getBody().items().isEmpty()) {
            throw new Px75ClientException("failed to fetch ESPN soccer leagues");
        }

        return response.getBody().items().stream()
                .map(item -> {
                    String league = item.ref().toString();
                    league = league.substring(league.indexOf("leagues/") + 8);
                    return league.substring(0, league.indexOf("?"));
                })
                .toList();
    }

    private SoccerMatch getMatch(final String competition, final String teamId) {
        final String matchId = getNextMatchId(competition, teamId);
        final ResponseEntity<NextMatchResponse.Event> nextMatchResponseEntity = espnSiteRestClient.get()
                .uri("soccer/{competition}/scoreboard/{matchId}/?ts={}", competition, matchId, System.currentTimeMillis())
                .header("User-Agent", RandomUserAgent.getRandomUserAgent())
                .retrieve()
                .toEntity(NextMatchResponse.Event.class);

        if (nextMatchResponseEntity.getStatusCode().is2xxSuccessful()) {
            log.info("Next match response: {}", nextMatchResponseEntity);

            if (nextMatchResponseEntity.getBody() != null) {
                return toSoccerMatch(nextMatchResponseEntity.getBody());
            } else {
                throw new Px75ClientException("failed to get next match, the response was empty");
            }
        } else {
            throw new HttpClientErrorException(nextMatchResponseEntity.getStatusCode());
        }
    }

    private String getNextMatchId(final String competition, final String teamId) {
        final ResponseEntity<JsonNode> nextMatchEntity = espnSiteRestClient.get()
                .uri("soccer/{competition}/teams/{teamId}/", competition, teamId)
                .retrieve()
                .toEntity(JsonNode.class);

        if (nextMatchEntity.getStatusCode().is2xxSuccessful() && nextMatchEntity.getBody() != null) {
            final JsonNode nextEventList = nextMatchEntity.getBody().get("team").get("nextEvent");
            if(nextEventList != null && !nextEventList.isEmpty()) {
                return nextEventList.get(0).get("id").textValue();
            } else {
                log.debug("Next match response was empty for team {} and competition: {}", teamId, competition);
                throw new NextMatchNotFoundException(teamId, competition);
            }
        } else {
            throw new HttpClientErrorException(nextMatchEntity.getStatusCode());
        }
    }

    public SoccerMatch toSoccerMatch(NextMatchResponse.Event event) {
        final Instant instant = OffsetDateTime.parse(event.getDate()).toInstant();
        final LocalDateTime localDateTime = LocalDateTime.ofInstant(instant, ZoneId.systemDefault());

        final NextMatchResponse.Competition competition = event.getCompetitions().getFirst();
        final NextMatchResponse.Status status = competition.getStatus();
        final List<NextMatchResponse.Competitor> competitors = competition.getCompetitors();

        final NextMatchResponse.Competitor homeCompetitor = competitors.stream()
                .filter(c -> "home".equalsIgnoreCase(c.getHomeAway()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No home team found"));

        final NextMatchResponse.Competitor awayCompetitor = competitors.stream()
                .filter(c -> "away".equalsIgnoreCase(c.getHomeAway()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No away team found"));

        final SoccerTeam home = new SoccerTeam(
                homeCompetitor.getTeam().getLocation(),   // name
                homeCompetitor.getTeam().getDisplayName(),   // displayName (API does not separate name vs displayName cleanly)
                homeCompetitor.getTeam().getAbbreviation(),
                homeCompetitor.getHomeAway(),
                homeCompetitor.getScore(),
                homeCompetitor.getTeam().getColor(),
                homeCompetitor.getTeam().getAlternateColor()
        );

        final SoccerTeam away = new SoccerTeam(
                awayCompetitor.getTeam().getLocation(),
                awayCompetitor.getTeam().getDisplayName(),
                awayCompetitor.getTeam().getAbbreviation(),
                awayCompetitor.getHomeAway(),
                awayCompetitor.getScore(),
                awayCompetitor.getTeam().getColor(),
                awayCompetitor.getTeam().getAlternateColor()
        );

        return new SoccerMatch(localDateTime, competition.isRecent(), status.getDisplayClock(), status.getPeriod(), home, away, status.getType().isCompleted());
    }
}
