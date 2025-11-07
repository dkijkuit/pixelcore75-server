package nl.ctasoftware.crypto.ticker.server.service.screen.crypto;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.CoinPriceHistory;
import nl.ctasoftware.crypto.ticker.server.model.CoinPricePercentage;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CryptoScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.screen.ScreenService;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CoinCurrency;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoAPIClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoClientCurrency;
import org.springframework.stereotype.Service;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
public class CryptoScreenService implements ScreenService<CryptoScreenConfig> {
    final ImageService imageService;
    final PaintToolsService paintToolsService;
    final Font miniLineFont8Px;
    final Font habboFont8Px;
    final Font ledBoardFont8Px;
    final CryptoAPIClient cryptoAPIClient;

    public CryptoScreenService(final ImageService imageService,
                               final CryptoAPIClient cryptoAPIClient, final PaintToolsService paintToolsService,
                               final Font miniLineFont8Px, final Font habboFont8Px, final Font ledBoardFont8Px) {
        this.imageService = imageService;
        this.cryptoAPIClient = cryptoAPIClient;
        this.paintToolsService = paintToolsService;
        this.miniLineFont8Px = miniLineFont8Px;
        this.habboFont8Px = habboFont8Px;
        this.ledBoardFont8Px = ledBoardFont8Px;
    }

    private BufferedImage getTickerImageForSymbol(final CryptoClientCurrency currency, final String symbol) throws IOException {
        final List<CoinPriceHistory> coinPriceHistoryList = cryptoAPIClient.getCoinPriceHistory(currency.name(), symbol);
        final CoinPricePercentage pricePercentageChangePercentage24H = cryptoAPIClient.getCoinPricePercentage(symbol);

        final CoinPriceHistory highestValue = getHighestPriceValue(coinPriceHistoryList);
        final CoinPriceHistory lowestValue = getLowestPriceValue(coinPriceHistoryList).orElseThrow();

        final BufferedImage bufferedImage = paintToolsService.newImage();
        final BigDecimal percentage = getPercentage(pricePercentageChangePercentage24H);

        drawCoinSymbol(bufferedImage, pricePercentageChangePercentage24H);
        draw24hPercentage(bufferedImage, percentage, pricePercentageChangePercentage24H);
        drawCurrentPrice(bufferedImage, coinPriceHistoryList, CoinCurrency.getCurrencySymbol(currency));
        drawSparkline(highestValue, lowestValue, coinPriceHistoryList, bufferedImage);

        return bufferedImage;
    }

    private void drawSparkline(final CoinPriceHistory highestValue, final CoinPriceHistory lowestValue, final List<CoinPriceHistory> coinPriceHistoryList, final BufferedImage bufferedImage) {
        final double delta = highestValue.price() - lowestValue.price();
        final int minIndex = coinPriceHistoryList.size() > 64 ? coinPriceHistoryList.size() - 64 : 0;
        for (int i = coinPriceHistoryList.size() - 1; i >= minIndex; i--) {
            final CoinPriceHistory coinPriceHistory = coinPriceHistoryList.get(i);
            final double relativePrice = 14 - ((coinPriceHistory.price() - lowestValue.price()) / delta) * 14;
            paintToolsService.drawSparkLine(bufferedImage, i - minIndex, 31, 17 + (int) relativePrice, new Color(180, 180, 180), Color.BLUE);
        }
    }

    private void drawCurrentPrice(final BufferedImage bufferedImage, final List<CoinPriceHistory> coinPriceHistoryList, final String currencySymbol) {
        paintToolsService.drawText(bufferedImage, ledBoardFont8Px, currencySymbol + coinPriceHistoryList.getLast().formattedPrice(), 0, 15, new Color(255, 88, 0));
    }

    private void draw24hPercentage(final BufferedImage bufferedImage, final BigDecimal percentage, final CoinPricePercentage pricePercentageChangePercentage24H) {
        paintToolsService.drawTextAlignRight(bufferedImage, ledBoardFont8Px, percentage.toPlainString() + "%", 7, pricePercentageChangePercentage24H.priceChangePercentage24h() < 0 ? Color.RED : Color.GREEN);
    }

    private void drawCoinSymbol(final BufferedImage bufferedImage, final CoinPricePercentage pricePercentageChangePercentage24H) {
        paintToolsService.drawText(bufferedImage, ledBoardFont8Px, pricePercentageChangePercentage24H.coinSymbol().toUpperCase(), 0, 7, Color.BLUE);
    }

    private static BigDecimal getPercentage(final CoinPricePercentage pricePercentageChangePercentage24H) {
        return BigDecimal.valueOf(pricePercentageChangePercentage24H.priceChangePercentage24h()).setScale(2, RoundingMode.HALF_UP);
    }

    private static Optional<CoinPriceHistory> getLowestPriceValue(final List<CoinPriceHistory> coinPriceHistoryList) {
        return coinPriceHistoryList.stream().min(Comparator.comparing(CoinPriceHistory::price));
    }

    private static CoinPriceHistory getHighestPriceValue(final List<CoinPriceHistory> coinPriceHistoryList) {
        return coinPriceHistoryList.stream().max(Comparator.comparing(CoinPriceHistory::price)).orElseThrow();
    }

    @Override
    public ScreenType getScreenType() {
        return ScreenType.CRYPTO_TICKER;
    }

    @Override
    public Optional<BufferedImage> renderScreen(final CryptoScreenConfig cryptoScreenConfig) {
        try {
            log.info("Next crypto: {}, in currency: {}", cryptoScreenConfig.config().symbol(), cryptoScreenConfig.config().currency());
            return Optional.of(getTickerImageForSymbol(CryptoClientCurrency.valueOf(cryptoScreenConfig.config().currency()), cryptoScreenConfig.config().symbol()));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
