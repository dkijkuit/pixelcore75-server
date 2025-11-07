package nl.ctasoftware.crypto.ticker.server.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

public record CoinPriceHistory(
        long date,
        double price
) {
    public String formattedPrice() {
        int scale;
        if (price < 1) {
            scale = 7;
        } else if (price < 10) {
            scale = 6;
        } else if (price < 100) {
            scale = 5;
        } else if (price < 1000) {
            scale = 4;
        } else if (price < 10000) {
            scale = 3;
        } else {
            scale = 2;
        }

        return BigDecimal.valueOf(price).setScale(scale, RoundingMode.HALF_UP).toPlainString();
    }
}