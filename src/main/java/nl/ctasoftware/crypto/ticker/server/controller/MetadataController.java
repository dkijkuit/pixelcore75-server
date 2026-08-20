package nl.ctasoftware.crypto.ticker.server.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CoinCurrency;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CoinSummary;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoAPIClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoClientCurrency;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CurrencySummary;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.LeagueSummary;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.SoccerMatchClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.client.TeamSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/metadata")
public class MetadataController {
    final SoccerMatchClient soccerMatchClient;
    final CryptoAPIClient cryptoAPIClient;

    @GetMapping("soccer/leagues")
    public List<LeagueSummary> getLeagues() {
        return soccerMatchClient.getLeagues().stream()
                .sorted(Comparator.comparing(LeagueSummary::name))
                .toList();
    }

    @GetMapping("soccer/teams")
    public List<TeamSummary> getTeams(@RequestParam("competition") final String competition) {
        return soccerMatchClient.getTeams(competition).stream()
                .sorted(Comparator.comparing(TeamSummary::name))
                .toList();
    }

    @GetMapping("crypto/coins")
    public List<CoinSummary> getCoins() {
        return cryptoAPIClient.getCoins();
    }

    @GetMapping("crypto/currencies")
    public List<CurrencySummary> getCurrencies() {
        return Arrays.stream(CryptoClientCurrency.values())
                .map(currency -> new CurrencySummary(currency.name(), CoinCurrency.getCurrencySymbol(currency)))
                .toList();
    }
}
