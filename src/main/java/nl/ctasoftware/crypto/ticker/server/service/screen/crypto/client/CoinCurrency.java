package nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client;

import java.util.Map;

public interface CoinCurrency {
    Map<CryptoClientCurrency, String> getCurrencies();

    default String getCurrency(final CryptoClientCurrency currency) {
        return getCurrencies().get(currency);
    }

    static String getCurrencySymbol(final CryptoClientCurrency currency) {
        return switch (currency) {
            case EURO -> "€";
            case US_DOLLAR -> "$";
            case BRITISH_POUND -> "£";
        };
    }
}
