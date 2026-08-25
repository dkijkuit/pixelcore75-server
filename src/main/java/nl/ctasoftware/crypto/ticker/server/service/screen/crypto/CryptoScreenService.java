package nl.ctasoftware.crypto.ticker.server.service.screen.crypto;

import lombok.extern.slf4j.Slf4j;
import nl.ctasoftware.crypto.ticker.server.model.CoinPriceHistory;
import nl.ctasoftware.crypto.ticker.server.model.CoinPricePercentage;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CryptoScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdLayout;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandBatch;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.command.Rgb565;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.image.ImageService;
import nl.ctasoftware.crypto.ticker.server.service.screen.CommandScreenService;
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
public class CryptoScreenService implements CommandScreenService<CryptoScreenConfig> {
    /** ACMD font page id of the ticker font (shared convention: 0 = EXEPixelPerfect@16f). */
    static final int PAGE_LEDBOARD_ID = 0;

    static final Color SPARK_LINE_COLOR = new Color(180, 180, 180);

    final ImageService imageService;
    final PaintToolsService paintToolsService;
    final Font miniLineFont8Px;
    final Font habboFont8Px;
    final Font ledBoardFont8Px;
    final CryptoAPIClient cryptoAPIClient;

    /** Extracted FONT page of the ticker font; computed lazily (extraction is deterministic). */
    private volatile FontPageExtractor.FontPage ledBoardPage;

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

    /* --------------------------------------------------------------------
     * ACMD command path: the same header texts (symbol / 24h percentage /
     * price) as led-board TEXT, the sparkline as one vertical LINE + PIX per
     * history point — the identical geometry the frame path's drawSparkLine
     * paints (gray column from y=31 up to the sample, blue sample pixel on
     * top). ACMD TEXT payloads are ASCII 32..126 by spec, so the non-ASCII
     * currency symbols (€/£) BLIT as drawString-rasterized glyphs — the exact
     * pixels the frame path paints for them, since the TTF carries both —
     * with the price TEXT starting at the symbol's AWT advance, the frame
     * path's own pen position within the combined string.
     * ------------------------------------------------------------------ */

    /** A currency-symbol glyph rasterized via the frame path's AWT pipeline. */
    record CurrencyGlyph(int[] pixels, int w, int h, int topFromBaseline, int advance) {
    }

    private static final int GLYPH_RASTER_ORIGIN = 32;

    /** Rasterized non-ASCII currency glyphs in the price color (deterministic, lazily filled). */
    private final java.util.Map<Character, CurrencyGlyph> currencyGlyphs = new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public byte[] renderCommandBatch(final CryptoScreenConfig cryptoScreenConfig) {
        final CryptoClientCurrency currency =
                CryptoClientCurrency.valueOf(cryptoScreenConfig.config().currency());
        final String symbol = cryptoScreenConfig.config().symbol();
        final List<CoinPriceHistory> coinPriceHistoryList = cryptoAPIClient.getCoinPriceHistory(currency.name(), symbol);
        final CoinPricePercentage pricePercentageChangePercentage24H = cryptoAPIClient.getCoinPricePercentage(symbol);

        final CoinPriceHistory highestValue = getHighestPriceValue(coinPriceHistoryList);
        final CoinPriceHistory lowestValue = getLowestPriceValue(coinPriceHistoryList).orElseThrow();
        final BigDecimal percentage = getPercentage(pricePercentageChangePercentage24H);

        final FontPageExtractor.FontPage page = ledBoardPage();
        final CommandBatch batch = CommandBatch.builder()
                .cls(AcmdMirror.BLACK)
                .fontPage(PAGE_LEDBOARD_ID, page.glyphs());

        AcmdLayout.left(batch, page, PAGE_LEDBOARD_ID,
                pricePercentageChangePercentage24H.coinSymbol().toUpperCase(), 0, 7, Color.BLUE);
        AcmdLayout.right(batch, page, PAGE_LEDBOARD_ID, percentage.toPlainString() + "%", 7,
                pricePercentageChangePercentage24H.priceChangePercentage24h() < 0 ? Color.RED : Color.GREEN);
        drawPrice(batch, page, CoinCurrency.getCurrencySymbol(currency),
                coinPriceHistoryList.getLast().formattedPrice());

        final int gray = Rgb565.of(SPARK_LINE_COLOR);
        final int blue = Rgb565.of(Color.BLUE);
        final double delta = highestValue.price() - lowestValue.price();
        final int minIndex = coinPriceHistoryList.size() > 64 ? coinPriceHistoryList.size() - 64 : 0;
        for (int i = coinPriceHistoryList.size() - 1; i >= minIndex; i--) {
            final CoinPriceHistory coinPriceHistory = coinPriceHistoryList.get(i);
            final double relativePrice = 14 - ((coinPriceHistory.price() - lowestValue.price()) / delta) * 14;
            final int endY = 17 + (int) relativePrice;
            batch.line(i - minIndex, 31, i - minIndex, endY, gray);
            batch.pix(i - minIndex, endY, blue);
        }

        return batch.build();
    }

    private FontPageExtractor.FontPage ledBoardPage() {
        FontPageExtractor.FontPage page = ledBoardPage;
        if (page == null) {
            page = FontPageExtractor.extract(ledBoardFont8Px);
            ledBoardPage = page;
        }
        return page;
    }

    private static final Color PRICE_COLOR = new Color(255, 88, 0);
    private static final int PRICE_BASELINE = 15;

    /**
     * The price line: a single TEXT for the ASCII {@code $}; for the non-ASCII
     * {@code €}/{@code £} a BLIT of the AWT-rasterized glyph (the exact pixels the
     * frame path's {@code drawString} paints — same font, AA off, alpha threshold
     * 128, transparent &rarr; black like the black canvas) plus the price TEXT at
     * the symbol's advance, the frame path's own pen position. A glyph the raster
     * finds empty (font without it) degrades to the sanitized single TEXT.
     */
    private void drawPrice(final CommandBatch batch, final FontPageExtractor.FontPage page,
                           final String currencySymbol, final String price) {
        if (currencySymbol.length() == 1 && (currencySymbol.charAt(0) < 32 || currencySymbol.charAt(0) > 126)) {
            final CurrencyGlyph glyph = currencyGlyphs.computeIfAbsent(currencySymbol.charAt(0),
                    this::rasterizeCurrencyGlyph);
            if (glyph != null) {
                batch.blit(0, PRICE_BASELINE + glyph.topFromBaseline(), glyph.w(), glyph.h(), glyph.pixels());
                AcmdLayout.left(batch, page, PAGE_LEDBOARD_ID, price, glyph.advance(), PRICE_BASELINE, PRICE_COLOR);
                return;
            }
        }
        AcmdLayout.left(batch, page, PAGE_LEDBOARD_ID, currencySymbol + price, 0, PRICE_BASELINE, PRICE_COLOR);
    }

    /**
     * Rasterizes one currency symbol exactly the way the frame path renders it:
     * {@code Graphics2D.drawString} with the led-board font, anti-aliasing off,
     * then the same &alpha;&ge;128 threshold + tight bbox the page extractor uses.
     * Returns {@code null} for a glyph the raster finds empty.
     */
    private CurrencyGlyph rasterizeCurrencyGlyph(final char symbol) {
        final String s = String.valueOf(symbol);
        final BufferedImage img = new BufferedImage(64, 32, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setFont(ledBoardFont8Px);
        final int advance = g.getFontMetrics().stringWidth(s);
        g.drawString(s, GLYPH_RASTER_ORIGIN, GLYPH_RASTER_ORIGIN);
        g.dispose();

        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) >>> 24) >= 128) {
                    minX = Math.min(minX, x);
                    minY = Math.min(minY, y);
                    maxX = Math.max(maxX, x);
                    maxY = Math.max(maxY, y);
                }
            }
        }
        if (maxX < 0) {
            return null;
        }

        final int w = maxX - minX + 1;
        final int h = maxY - minY + 1;
        final int[] pixels = new int[w * h];
        final int color = Rgb565.of(PRICE_COLOR);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                pixels[y * w + x] = (img.getRGB(minX + x, minY + y) >>> 24) >= 128
                        ? color
                        : AcmdMirror.BLACK;
            }
        }
        return new CurrencyGlyph(pixels, w, h, minY - GLYPH_RASTER_ORIGIN, advance);
    }
}
