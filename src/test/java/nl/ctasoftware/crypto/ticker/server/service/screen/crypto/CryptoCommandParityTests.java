package nl.ctasoftware.crypto.ticker.server.service.screen.crypto;

import nl.ctasoftware.crypto.ticker.server.model.CoinPriceHistory;
import nl.ctasoftware.crypto.ticker.server.model.CoinPricePercentage;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CryptoConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CryptoScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.CommandGolden;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoAPIClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden snapshots for the CRYPTO command screen (plan §6): the ACMD batch's
 * {@link nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror} frame in panel
 * RGB565, pinned per mocked CoinGecko data. The header TEXTs share the led-board font
 * page; the sparkline is one vertical LINE + PIX per history point (gray column up to
 * the sample, blue sample pixel on top). The non-ASCII currency symbols (€/£) BLIT as
 * AWT-rasterized glyphs at the symbol's advance.
 */
class CryptoCommandParityTests {

    private CryptoScreenService service;
    private CryptoAPIClient client;

    @BeforeEach
    void setUp() {
        final Font ledBoard = FontPageExtractor.loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        client = mock(CryptoAPIClient.class);
        service = new CryptoScreenService(client, ledBoard);
    }

    /** 64 samples of a smooth wave — every sparkline column height is exercised. */
    private static List<CoinPriceHistory> history() {
        final List<CoinPriceHistory> history = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            history.add(new CoinPriceHistory(1_700_000_000L + i * 3600_000L,
                    100 + 40 * Math.sin(i * Math.PI / 16)));
        }
        return history;
    }

    private void assertGoldenFor(final String name, final String currency, final double change24h) {
        when(client.getCoinPriceHistory(currency, "bitcoin")).thenReturn(history());
        when(client.getCoinPricePercentage("bitcoin")).thenReturn(new CoinPricePercentage("btc", change24h));
        final CryptoScreenConfig config =
                new CryptoScreenConfig(ScreenType.CRYPTO_TICKER, 5, new CryptoConfig("bitcoin", currency));
        CommandGolden.assertGolden(name, CommandGolden.frameAt(service.renderCommandBatch(config), 0));
    }

    @Test
    void dollarTickerWithLossMatchesGolden() {
        assertGoldenFor("crypto-dollar", "US_DOLLAR", -2.45);
    }

    @Test
    void euroSymbolBlitsTheRasterizedGlyph() {
        // ACMD payloads are ASCII by spec, so € cannot ride in a TEXT — the command
        // path BLITs the AWT-rasterized glyph (the TTF carries it) and starts the
        // price TEXT at the symbol's advance.
        assertGoldenFor("crypto-euro", "EURO", 1.13);
    }

    @Test
    void poundSymbolBlitsTheRasterizedGlyph() {
        assertGoldenFor("crypto-pound", "BRITISH_POUND", -0.87);
    }
}
