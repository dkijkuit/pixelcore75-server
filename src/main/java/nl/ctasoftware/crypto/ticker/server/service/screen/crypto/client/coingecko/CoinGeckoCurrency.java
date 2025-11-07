package nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.coingecko;

import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CoinCurrency;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoClientCurrency;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@ConditionalOnProperty(name = "pixelcore75.crypto.client", havingValue = "coingecko")
public class CoinGeckoCurrency implements CoinCurrency {
    private static final Map<CryptoClientCurrency, String> CURRENCIES = Map.of(
            CryptoClientCurrency.EURO, "eur",
            CryptoClientCurrency.US_DOLLAR, "usd",
            CryptoClientCurrency.BRITISH_POUND, "gbp"
    );

    @Override
    public Map<CryptoClientCurrency, String> getCurrencies() {
        return CURRENCIES;
    }
}
