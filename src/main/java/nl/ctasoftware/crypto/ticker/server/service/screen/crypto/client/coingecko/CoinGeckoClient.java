package nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.coingecko;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.CoinPriceHistory;
import nl.ctasoftware.crypto.ticker.server.model.CoinPricePercentage;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoAPIClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoClientCurrency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pixelcore75.crypto.client", havingValue = "coingecko")
public class CoinGeckoClient implements CryptoAPIClient {
    final RestClient coinGeckoRestClient;

    @Override
    @Cacheable("coingeckoPricePercentage")
    public CoinPricePercentage getCoinPricePercentage(final String symbol) {
        log.info("Getting price percentage for symbol {}", symbol);

        final JsonNode result = coinGeckoRestClient.get()
                .uri("/" + symbol + "/?market_data=true&developer_data=false&sparkline=false&tickers=false&localization=false&community_data=false")
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();

        return new CoinPricePercentage(result.get("symbol").asText(), result.get("market_data").get("price_change_percentage_24h").asDouble());
    }

    @Override
    @Cacheable("coingeckoPriceHistory")
    public List<CoinPriceHistory> getCoinPriceHistory(final String currency, final String symbol) {
        log.info("Getting price history for symbol {}", symbol);

        final CoinGeckoCurrency coinGeckoCurrency = new CoinGeckoCurrency();
        final String coinGeckoCurrencyStr = coinGeckoCurrency.getCurrency(CryptoClientCurrency.valueOf(currency));

        final JsonNode result = coinGeckoRestClient.get()
                .uri("/" + symbol + "/market_chart?vs_currency=" + coinGeckoCurrencyStr + "&days=3")
                .retrieve()
                .toEntity(JsonNode.class)
                .getBody();

        return result.get("prices").valueStream().map(node -> new CoinPriceHistory(node.get(0).asLong(), node.get(1).asDouble())).toList();
    }
}
