package nl.ctasoftware.crypto.ticker.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

@Slf4j
@Configuration
public class ClientConfig {
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
}
