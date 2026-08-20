package nl.ctasoftware.crypto.ticker.server.service.job;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobSchedulerServiceTests {

    private ThreadPoolTaskScheduler scheduler;
    private ExecutorService worker;
    private JobSchedulerService service;

    private final List<String> log = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        if (service != null) service.stopAll();
        if (scheduler != null) scheduler.shutdown();
        if (worker != null) worker.shutdownNow();
    }

    private void newService() {
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.initialize();
        worker = Executors.newVirtualThreadPerTaskExecutor();
        service = new JobSchedulerService(scheduler, worker);
    }

    private static void await(CountDownLatch latch) throws InterruptedException {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "latch not released within timeout");
    }

    private static void settle() throws InterruptedException {
        Thread.sleep(300);
    }

    @Test
    void replacingJobWhilePreviousRunInFlightKeepsReplacementStoppable() throws Exception {
        newService();
        var aStarted = new CountDownLatch(1);
        var bStarted = new CountDownLatch(1);
        var releaseA = new CountDownLatch(1);

        var jobA = new ReschedulableJob() {
            @Override public String getId() { return "panel-1"; }
            @Override public Optional<Duration> run() throws Exception {
                log.add("a");
                aStarted.countDown();
                releaseA.await();
                return Optional.of(Duration.ofSeconds(60));
            }
        };
        var jobB = new ReschedulableJob() {
            @Override public String getId() { return "panel-1"; }
            @Override public Optional<Duration> run() {
                log.add("b");
                bStarted.countDown();
                return Optional.of(Duration.ofSeconds(60));
            }
        };

        service.schedule(jobA, Duration.ZERO);
        await(aStarted);

        assertTrue(service.stop("panel-1", false));
        service.schedule(jobB, Duration.ZERO);
        await(bStarted);

        releaseA.countDown();
        settle();

        assertTrue(service.get("panel-1").isPresent(), "replacement job must stay registered after the stopped job's in-flight run completes");
        assertTrue(service.stop("panel-1", false), "replacement job must be stoppable");
    }

    @Test
    void scheduleOverExistingJobCancelsThePreviousJob() throws Exception {
        newService();
        var aStarted = new CountDownLatch(1);
        var releaseA = new CountDownLatch(1);

        var jobA = new ReschedulableJob() {
            @Override public String getId() { return "panel-1"; }
            @Override public Optional<Duration> run() throws Exception {
                log.add("a");
                aStarted.countDown();
                releaseA.await();
                return Optional.of(Duration.ofMillis(50));
            }
        };
        var jobB = new ReschedulableJob() {
            @Override public String getId() { return "panel-1"; }
            @Override public Optional<Duration> run() {
                log.add("b");
                return Optional.of(Duration.ofSeconds(60));
            }
        };

        service.schedule(jobA, Duration.ZERO);
        await(aStarted);
        service.schedule(jobB, Duration.ZERO);

        releaseA.countDown();
        settle();

        assertFalse(log.contains("a:a"), "job replaced under the same id must not run again: " + log);
        service.stop("panel-1", false);
    }

    @Test
    void forceStopInterruptsInFlightRun() throws Exception {
        newService();
        var aStarted = new CountDownLatch(1);
        var interrupted = new CountDownLatch(1);
        var releaseA = new CountDownLatch(1); // nooit vrijgegeven: alleen de interrupt mag de run beëindigen

        var jobA = new ReschedulableJob() {
            @Override public String getId() { return "panel-1"; }
            @Override public Optional<Duration> run() throws Exception {
                log.add("a");
                aStarted.countDown();
                try {
                    releaseA.await(); // zoals de ack-waits in PanelScreenJob: interruptibel
                } catch (InterruptedException e) {
                    log.add("interrupted");
                    interrupted.countDown();
                    throw e;
                }
                log.add("a-finished-normally");
                return Optional.of(Duration.ofSeconds(60));
            }
        };

        service.schedule(jobA, Duration.ZERO);
        await(aStarted);

        assertTrue(service.stop("panel-1", true), "force stop must find the running job");
        await(interrupted); // de lopende run wordt onderbroken in plaats van een slot lang door te lopen

        settle();
        assertFalse(log.contains("a-finished-normally"), "force-stopped run must not complete normally: " + log);
        assertTrue(service.get("panel-1").isEmpty(), "stopped job must be removed from the registry");
    }
}
