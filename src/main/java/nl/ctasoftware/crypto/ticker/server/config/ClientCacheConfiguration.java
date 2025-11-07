package nl.ctasoftware.crypto.ticker.server.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import nl.ctasoftware.crypto.ticker.server.service.screen.soccer.SoccerMatch;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.cache.support.SimpleCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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

        cacheManager.registerCustomCache("formula1", Caffeine.newBuilder()
                .initialCapacity(1)
                .maximumSize(100)
                .expireAfterWrite(60, TimeUnit.MINUTES)
                .build());

        return cacheManager;
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

        var manager = new SimpleCacheManager();
        manager.setCaches(List.of(soccerMatchCache));
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