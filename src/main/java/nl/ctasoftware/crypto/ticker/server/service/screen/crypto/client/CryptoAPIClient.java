package nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client;

import nl.ctasoftware.crypto.ticker.server.model.CoinPriceHistory;
import nl.ctasoftware.crypto.ticker.server.model.CoinPricePercentage;

import java.util.List;

public interface CryptoAPIClient {
    List<CoinPriceHistory> getCoinPriceHistory(String currency, String symbol);
    CoinPricePercentage getCoinPricePercentage(String symbol);
}
