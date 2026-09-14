package nl.ctasoftware.crypto.ticker.server.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.LoadingCache;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AdsbLolRequest;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.AdsbLolAircraftClient.AircraftApiProvider;
import nl.ctasoftware.crypto.ticker.server.service.screen.aircraft.client.NearbyAircraft;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatch;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.NullValue;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Configuration(proxyBeanMethods = false)
public class ClientCacheConfiguration {
    @Bean
    @Primary
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        cacheManager.registerCustomCache("usersByUsername", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(10_000)
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .build());

        cacheManager.registerCustomCache("usersByUserId", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(10_000)
                .expireAfterWrite(15, TimeUnit.MINUTES)
                .build());

        // CUSTOM library designs, read by the rotation's per-cycle hydration (RotationPlanner
        // → CustomScreenResolver): without this every panel pays one findById per referenced
        // library screen per cycle (~20 queries/min/panel). Short TTL — library saves/delete
        // evict explicitly, so this only bounds the staleness from any other path.
        cacheManager.registerCustomCache("customScreenDesigns", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(1000)
                .expireAfterWrite(10, TimeUnit.SECONDS)
                .build());

        cacheManager.registerCustomCache("coingeckoPricePercentage", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build());

        cacheManager.registerCustomCache("coingeckoPriceHistory", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build());

        cacheManager.registerCustomCache("coingeckoCoinList", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(1)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build());

        cacheManager.registerCustomCache("weatherForecast", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build());

        cacheManager.registerCustomCache("images", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build());

        cacheManager.registerCustomCache("animations", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build());

        cacheManager.registerCustomCache("formula1", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .build());

        // adsbdb registry data is static; routes change only per callsign flight. Unknown
        // hexes/callsigns (empty results, stored by Spring as its NullValue sentinel)
        // are negatively cached for 15 minutes — see AdsbdbExpiry below.
        cacheManager.registerCustomCache("adsbdbAircraft", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(1000)
                .expireAfter(new AdsbdbExpiry(TimeUnit.HOURS.toNanos(24)))
                .build());

        cacheManager.registerCustomCache("adsbdbRoute", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(1000)
                .expireAfter(new AdsbdbExpiry(TimeUnit.HOURS.toNanos(1)))
                .build());

        return cacheManager;
    }

    /**
     * Stale-while-revalidate for live aircraft positions (used directly by
     * {@link AdsbLolAircraftClient}, no Spring cache proxy): the first get() blocks for
     * the fetch-retry envelope; afterwards reads return the cached list instantly while
     * {@code refreshAfterWrite} re-fetches on the executor below — never the rendering
     * thread. Loads rotate adsb.lol → adsb.fi (sticky on the provider that last
     * answered). The 2 s window matches the radar command refresh cadence (blips move at
     * every republish); fetches only happen while something actually polls that fast,
     * so slower screens keep their once-per-slot fetch rate. An entry disappears 60 s
     * after its last write if untouched.
     */
    @Bean(destroyMethod = "close")
    ExecutorService adsbLolRefreshExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("adsblol-refresh-", 0).factory());
    }

    /**
     * The loader as its own bean so {@link AdsbLolAircraftClient} can run it directly
     * for foreground slot-start fetches ({@code getAircraftFresh}) — the cache itself
     * only exposes the stale-while-revalidate read.
     */
    @Bean
    AdsbLolAircraftClient.AdsbLolCacheLoader adsbLolCacheLoader(
            @Qualifier("adsbLolRestClient") final RestClient adsbLolRestClient,
            @Qualifier("adsbFiRestClient") final RestClient adsbFiRestClient) {
        return new AdsbLolAircraftClient.AdsbLolCacheLoader(List.of(
                new AircraftApiProvider("adsb.lol", adsbLolRestClient),
                new AircraftApiProvider("adsb.fi", adsbFiRestClient,
                        AircraftApiProvider.PointPathStyle.LAT_LON_DIST)));
    }

    @Bean
    LoadingCache<AdsbLolRequest, List<NearbyAircraft>> adsbLolNearbyCache(
            @Qualifier("adsbLolCacheLoader") final AdsbLolAircraftClient.AdsbLolCacheLoader adsbLolCacheLoader,
            @Qualifier("adsbLolRefreshExecutor") final ExecutorService adsbLolRefreshExecutor) {
        return Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(50)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .refreshAfterWrite(2, TimeUnit.SECONDS)
                .executor(adsbLolRefreshExecutor)
                .build(adsbLolCacheLoader);
    }

    /**
     * Variable TTL for the adsbdb caches: real hits are near-static (24 h aircraft /
     * 1 h route), while misses — Spring stores {@link Optional#empty()} results as its
     * {@link NullValue} sentinel for Optional-returning {@code @Cacheable} methods —
     * expire after 15 minutes so unknown lookups are re-checked without hammering adsbdb.
     */
    private static final class AdsbdbExpiry implements Expiry<Object, Object> {

        static final long NEGATIVE_TTL_NANOS = TimeUnit.MINUTES.toNanos(15);

        private final long positiveTtlNanos;

        AdsbdbExpiry(final long positiveTtlNanos) {
            this.positiveTtlNanos = positiveTtlNanos;
        }

        @Override
        public long expireAfterCreate(final Object key, final Object value, final long currentTimeNanos) {
            return ttl(value);
        }

        @Override
        public long expireAfterUpdate(final Object key, final Object value,
                                      final long currentTimeNanos, final long currentDurationNanos) {
            return ttl(value);
        }

        @Override
        public long expireAfterRead(final Object key, final Object value,
                                    final long currentTimeNanos, final long currentDurationNanos) {
            return currentDurationNanos; // keep remaining TTL on read
        }

        private long ttl(final Object value) {
            final boolean miss = value instanceof NullValue
                    || (value instanceof Optional<?> optional && optional.isEmpty());
            return miss ? NEGATIVE_TTL_NANOS : positiveTtlNanos;
        }
    }

    @Bean
    public CacheManager soccerCacheManager() {
        var nativeCache = Caffeine.newBuilder()
                .expireAfter(new Expiry<>() {
                    @Override
                    public long expireAfterCreate(Object key, Object value, long currentTimeNanos) {
                        return ttlNanos(value);
                    }

                    @Override
                    public long expireAfterUpdate(Object key, Object value,
                                                  long currentTimeNanos, long currentDurationNanos) {
                        return ttlNanos(value);
                    }

                    @Override
                    public long expireAfterRead(Object key, Object value,
                                                long currentTimeNanos, long currentDurationNanos) {
                        return currentDurationNanos; // keep remaining TTL on read
                    }

                    private long ttlNanos(Object value) {
                        if (!(value instanceof SoccerMatch m)) {
                            // Fallback: short TTL if something unexpected ends up in the cache
                            return TimeUnit.MINUTES.toNanos(1);
                        }

                        // Live -> don't cache
                        if (m.started() && !m.finished()) {
                            return 0L;
                        }

                        // Pre-match -> expire at kickoff OR in 1h, whichever is sooner
                        if (!m.started() && !m.finished()) {
                            Instant now = Instant.now();
                            Instant ko = m.date().atZone(ZoneId.systemDefault()).toInstant();
                            long untilKoSecs = Duration.between(now, ko).getSeconds();
                            long oneHourSecs = TimeUnit.HOURS.toSeconds(1);
                            long secs = Math.min(untilKoSecs, oneHourSecs);
                            return TimeUnit.SECONDS.toNanos(Math.max(1, secs));
                        }

                        // Post-match -> short TTL (e.g., 5 minutes)
                        return TimeUnit.MINUTES.toNanos(5);
                    }
                })
                .initialCapacity(1)
                .maximumSize(1000)
                .build();

        var soccerMatchCache = new CaffeineCache("soccerMatch", nativeCache);

        var soccerMetadataCache = new CaffeineCache("soccerMetadata", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(1000)
                .expireAfterWrite(24, TimeUnit.HOURS)
                .build());

        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(soccerMatchCache, soccerMetadataCache));
        return manager;
    }

    @Bean
    ClientHttpRequestInterceptor noCacheRequestInterceptor() {
        return (request, body, execution) -> {
            var h = request.getHeaders();
            h.setCacheControl("no-cache, no-store, max-age=0, must-revalidate");
            h.add(HttpHeaders.PRAGMA, "no-cache");
            return execution.execute(request, body);
        };
    }
}