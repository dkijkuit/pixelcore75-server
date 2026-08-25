package nl.ctasoftware.crypto.ticker.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

@Slf4j
@Configuration
public class ClientConfig {

    /** Shared wall clock (injectable/test-freezable), UTC. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
    @Bean
    RestClient coinGeckoRestClient(@Value("${pixelcore75.crypto.apikey}") final String apiKey, final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .baseUrl("https://api.coingecko.com/api/v3/coins/")
                    .defaultHeader("x-cg-demo-api-key", apiKey)
                .requestInterceptor(noCacheRequestInterceptor)
                .build();
    }

    @Bean
    RestClient openMeteoRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestInterceptor(noCacheRequestInterceptor)
                .baseUrl("https://api.open-meteo.com/v1/")
                .build();
    }

    @Bean
    RestClient espnSiteRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestInterceptor(noCacheRequestInterceptor)
                .baseUrl("http://site.api.espn.com/apis/site/v2/sports/")
                .build();
    }

    @Bean
    RestClient espnCoreApiRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor, @Value("${pixelcore75.soccer.basePath}") final String basePath) {
        return RestClient.builder()
                .requestInterceptor(noCacheRequestInterceptor)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .baseUrl(basePath)
                .build();
    }

    @Bean
    RestClient sofascoreRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestInterceptor(noCacheRequestInterceptor)
                .baseUrl("https://www.sofascore.com/api/v1/")
                .build();
    }

    @Bean
    RestClient jolpiRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestInterceptor(noCacheRequestInterceptor)
                .baseUrl("https://api.jolpi.ca/ergast/")
                .build();
    }

    @Bean
    RestClient sportsDbRestClient(
            RestClient.Builder builder,
            @Value("${pixelcore75.soccer.apikey}") String apiKey
    ) {
        return builder
                .baseUrl("https://www.thesportsdb.com/api/v2/json")
                .defaultHeader("X-API-KEY", apiKey) // V2 header auth
                .build();
    }

    @Bean
    RestClient adsbLolRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return aircraftApiRestClient("https://api.adsb.lol/v2/", noCacheRequestInterceptor);
    }

    /**
     * Failover provider for live aircraft positions: readsb-style v2 API like adsb.lol
     * (same aircraft fields, /mil endpoint), but the point query takes the
     * {@code /lat/{lat}/lon/{lon}/dist/{dist}} path shape and the list key is
     * {@code aircraft} instead of {@code ac} — the loader/wire DTOs absorb both
     * differences (adsb.lol ingress rate-limits with 429s or drops nodes entirely).
     */
    @Bean
    RestClient adsbFiRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return aircraftApiRestClient("https://opendata.adsb.fi/api/v2/", noCacheRequestInterceptor);
    }

    private static RestClient aircraftApiRestClient(final String baseUrl, final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestFactory(timedJdkRequestFactory())
                .requestInterceptor(noCacheRequestInterceptor)
                .defaultHeader(HttpHeaders.USER_AGENT, "pixelcore75")
                .baseUrl(baseUrl)
                .build();
    }

    @Bean
    RestClient adsbdbRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestFactory(timedJdkRequestFactory())
                .requestInterceptor(noCacheRequestInterceptor)
                .defaultHeader(HttpHeaders.USER_AGENT, "pixelcore75")
                .baseUrl("https://api.adsbdb.com/v0/")
                .build();
    }

    /** Spotify Web API (playback state, profile) — Bearer token added per request. */
    @Bean
    RestClient spotifyApiRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestFactory(timedJdkRequestFactory())
                .requestInterceptor(noCacheRequestInterceptor)
                .baseUrl("https://api.spotify.com/v1/")
                .build();
    }

    /** Spotify accounts host (authorize page + token endpoint) — PKCE, no client secret. */
    @Bean
    RestClient spotifyAccountsRestClient(final ClientHttpRequestInterceptor noCacheRequestInterceptor) {
        return RestClient.builder()
                .requestFactory(timedJdkRequestFactory())
                .requestInterceptor(noCacheRequestInterceptor)
                .baseUrl("https://accounts.spotify.com/")
                .build();
    }


    /**
     * The ADS-B APIs resolve to several ingress nodes of which some are intermittently
     * unreachable (TLS reset / hang): keep connect/read timeouts tight so a render
     * slot never blocks on a dead node; clients rotate providers and degrade gracefully.
     */
    private static JdkClientHttpRequestFactory timedJdkRequestFactory() {
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        final JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(5));
        return requestFactory;
    }
}
