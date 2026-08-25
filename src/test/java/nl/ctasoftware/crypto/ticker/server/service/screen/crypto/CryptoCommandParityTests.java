package nl.ctasoftware.crypto.ticker.server.service.screen.crypto;

import nl.ctasoftware.crypto.ticker.server.model.CoinPriceHistory;
import nl.ctasoftware.crypto.ticker.server.model.CoinPricePercentage;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CryptoConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.CryptoScreenConfig;
import nl.ctasoftware.crypto.ticker.server.model.panel.config.ScreenType;
import nl.ctasoftware.crypto.ticker.server.service.command.AcmdMirror;
import nl.ctasoftware.crypto.ticker.server.service.command.FrameParity;
import nl.ctasoftware.crypto.ticker.server.service.command.FontPageExtractor;
import nl.ctasoftware.crypto.ticker.server.service.image.PaintToolsService;
import nl.ctasoftware.crypto.ticker.server.service.screen.crypto.client.CryptoAPIClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Golden-image parity for the CRYPTO command screen (plan §6): the frame path's AWT
 * render vs the {@link AcmdMirror} of the command batch, both in panel RGB565, from
 * the same mocked CoinGecko data. The header TEXTs share the led-board font page;
 * the sparkline is one vertical LINE + PIX per history point — the identical geometry
 * {@code drawSparkLine} paints (gray column up to the sample, blue sample pixel on
 * top), and GFX's vertical line rasterization is pixel-exact against AWT's. The
 * non-ASCII currency symbols (€/£) BLIT as AWT-rasterized glyphs at the symbol's
 * advance, so every currency stays inside the 5% family budget.
 *
 * <p>Tolerance: &ge;95% pixel equality (mismatch budget 102 of 2048 px), the same
 * family as the clock/aircraft/spotify suites; the budget guards JDK font-rendering
 * variance, not an expected gap.</p>
 */
class CryptoCommandParityTests {

    private static final int MISMATCH_BUDGET = 102; // 5% of 2048

    private CryptoScreenService service;
    private CryptoAPIClient client;

    @BeforeEach
    void setUp() {
        final Font ledBoard = FontPageExtractor.loadFont(new File("assets/fonts/EXEPixelPerfect.ttf"), 16f);
        client = mock(CryptoAPIClient.class);
        service = new CryptoScreenService(null, client, new PaintToolsService(null, null, ledBoard),
                ledBoard, ledBoard, ledBoard);
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

    private int mismatches(final String currency, final double change24h) {
        when(client.getCoinPriceHistory(currency, "bitcoin")).thenReturn(history());
        when(client.getCoinPricePercentage("bitcoin")).thenReturn(new CoinPricePercentage("btc", change24h));
        final CryptoScreenConfig config =
                new CryptoScreenConfig(ScreenType.CRYPTO_TICKER, 5, new CryptoConfig("bitcoin", currency));
        final BufferedImage frame = service.renderScreen(config).orElseThrow();
        final int[] command = AcmdMirror.parse(service.renderCommandBatch(config)).frameAt(0);
        return FrameParity.mismatchedPixels(FrameParity.rgb565(frame), command);
    }

    @Test
    void dollarTickerWithLossMatchesAcrossPaths() {
        final int mismatch = mismatches("US_DOLLAR", -2.45);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "CRYPTO parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void euroSymbolBlitsTheFramePathGlyph() {
        // ACMD payloads are ASCII by spec, so € cannot ride in a TEXT — the command
        // path BLITs the AWT-rasterized glyph (the TTF carries it) and starts the
        // price TEXT at the symbol's advance, the frame path's own pen position.
        final int mismatch = mismatches("EURO", 1.13);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "CRYPTO euro parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }

    @Test
    void poundSymbolBlitsTheFramePathGlyph() {
        final int mismatch = mismatches("BRITISH_POUND", -0.87);
        assertTrue(mismatch <= MISMATCH_BUDGET,
                "CRYPTO pound parity: " + mismatch + " mismatched px, budget " + MISMATCH_BUDGET);
    }
}
