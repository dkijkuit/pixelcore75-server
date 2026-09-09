package nl.ctasoftware.crypto.ticker.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

@Slf4j
@Configuration
public class SchedulerConfiguration {
    @Bean(destroyMethod = "close")
    public ExecutorService sseExecutor() {
        var vts = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        return new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(vts);
    }

    @Bean
    @Qualifier("sseScheduler")
    public ScheduledExecutorService sseScheduler() {
        return Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "sse-heartbeat");
            t.setDaemon(true);
            return t;
        });
    }
}
