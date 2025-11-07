package nl.ctasoftware.crypto.ticker.server.model;

public record CoinPricePercentage(
        String coinSymbol,
        double priceChangePercentage24h
) {
}