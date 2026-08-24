package nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client;

import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AdsbLolCacheLoader;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AdsbLolRequest;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AircraftApiProvider;
import nl.ctasoftware.crypto.ticker.server.service.screen.weather.LatLon;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Freshness contract of the adsb.lol stale-while-revalidate cache, mirroring the
 * production wiring ({@code ClientCacheConfiguration}: refreshAfterWrite 2 s,
 * expireAfterWrite 60 s, background reload executor): a read returns the cached
 * snapshot instantly and re-fetches in the background, so a once-per-slot screen
 * reading a key that nothing polled recently (CLOSEST next to RADAR in a rotation)
 * would show the PREVIOUS fetch's data — the "closest aircraft screen shows older
 * data than the radar screen" bug. {@link AircraftClient#getAircraftFresh} is the
 * fix path for those slot-start renders; the RADAR refresh supplier must keep the
 * instant read. The loader is stubbed (no HTTP): every load returns a list tagged
 * with its sequence number.
 */
class AdsbLolAircraftClientFreshnessTests {

    private static final LatLon ORIGIN = new LatLon(52.0, 4.0);

    /** Loader stub: no HTTP; each load returns a single aircraft tagged with its call count. */
    private static final class RecordingLoader extends AdsbLolCacheLoader {
        final AtomicInteger loads = new AtomicInteger();
        volatile long loadDelayMs;

        RecordingLoader() {
            super(List.of(new AircraftApiProvider("stub", RestClient.create())));
        }

        @Override
        public List<NearbyAircraft> load(final AdsbLolRequest request) {
            final int seq = loads.incrementAndGet();
            try {
                Thread.sleep(loadDelayMs);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.of(new NearbyAircraft("00000" + seq, "AC" + seq, null, null, null,
                    10_000 + seq, false, 250.0, 90.0, 0, 5.0 + seq, 60.0));
        }
    }

    private final RecordingLoader loader = new RecordingLoader();
    private ExecutorService reloadExecutor;
    private AdsbLolAircraftClient client;

    @BeforeEach
    void setUp() {
        reloadExecutor = Executors.newSingleThreadExecutor();
        final LoadingCache<AdsbLolRequest, List<NearbyAircraft>> cache = Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(50)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .refreshAfterWrite(2, TimeUnit.SECONDS)
                .executor(reloadExecutor)
                .build(loader);
        client = new AdsbLolAircraftClient(cache, loader);
    }

    @AfterEach
    void tearDown() {
        reloadExecutor.shutdownNow();
    }

    private static String callsign(final List<NearbyAircraft> aircraft) {
        return aircraft.isEmpty() ? "(none)" : aircraft.getFirst().callsign();
    }

    /** Ages the cache entry past refreshAfterWrite (2 s) but well under expiry (60 s). */
    private static void letEntryGoRefreshStale() {
        try {
            Thread.sleep(2_500);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void slotStartReadFetchesCurrentDataInsteadOfServingTheStaleSnapshot() {
        assertEquals("AC1", callsign(client.getAircraft(ORIGIN, 25, false)), "initial read blocks for load 1");
        letEntryGoRefreshStale();

        // The regression: this read is what CLOSEST renders at its slot start. The SWR
        // read below it serves AC1 and only re-fetches in the background; the fresh
        // read must show the data fetched NOW (load 2), not the previous fetch's.
        final List<NearbyAircraft> shown = client.getAircraftFresh(ORIGIN, 25, false);

        assertEquals("AC2", callsign(shown), "slot-start render shows the fetch made at display time");
        assertEquals(2, loader.loads.get(), "exactly one foreground fetch");
    }

    @Test
    void swrReadServesTheCachedSnapshotAndRevalidatesInBackground() {
        assertEquals("AC1", callsign(client.getAircraft(ORIGIN, 25, false)));
        letEntryGoRefreshStale();

        // Documents why RADAR never shows stale data (it polls every 2 s) and why the
        // once-per-slot CLOSEST did: the SWR read returns the old snapshot instantly
        // and the fresh value only lands for the NEXT reader.
        assertEquals("AC1", callsign(client.getAircraft(ORIGIN, 25, false)));
        await().atMost(5, TimeUnit.SECONDS).until(() -> loader.loads.get() >= 2);
    }

    @Test
    void swrReadNeverBlocksOnASlowProvider() {
        assertEquals("AC1", callsign(client.getAircraft(ORIGIN, 25, false)));
        loader.loadDelayMs = 300;
        letEntryGoRefreshStale();

        // The RADAR refresh supplier renders on a 2 s grid: its read must return the
        // cached value instantly while the (slow) revalidation happens elsewhere.
        final long start = System.nanoTime();
        final List<NearbyAircraft> served = client.getAircraft(ORIGIN, 25, false);
        final long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals("AC1", callsign(served));
        assertTrue(elapsedMs < 150, "SWR read served from cache, took " + elapsedMs + " ms");
    }
}
