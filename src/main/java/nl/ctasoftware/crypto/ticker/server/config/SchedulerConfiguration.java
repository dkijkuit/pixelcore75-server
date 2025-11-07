package nl.ctasoftware.crypto.ticker.server.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

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

    @Bean
    public ThreadPoolTaskScheduler jobScheduler() {
        var ts = new ThreadPoolTaskScheduler();
        ts.setPoolSize(8);
        ts.setThreadNamePrefix("job-trigger-");
        ts.initialize();
        return ts;
    }

    // Een aparte executor voor de echte job-runner:
    @Bean(destroyMethod = "close")
    public ExecutorService jobWorker() {
        ThreadFactory tf = Thread.ofVirtual()
                .name("panelscreenjob-worker-", 0)
                .uncaughtExceptionHandler((t, e) ->
                        log.error("Uncaught in {}", t.getName(), e))
                .factory();
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
