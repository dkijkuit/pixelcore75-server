package nl.ctasoftware.crypto.ticker.server;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@Slf4j
@EnableCaching
@EnableScheduling
@SpringBootApplication
@RequiredArgsConstructor
public class CryptoTickerServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(CryptoTickerServerApplication.class, args);
    }
}
