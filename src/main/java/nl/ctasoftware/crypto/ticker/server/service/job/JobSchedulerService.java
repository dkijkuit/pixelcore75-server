package nl.ctasoftware.crypto.ticker.server.service.job;

import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class JobSchedulerService {

    private final ThreadPoolTaskScheduler scheduler;
    private final ExecutorService worker; // virtuele-threads executor

    public JobSchedulerService(final ThreadPoolTaskScheduler jobScheduler, final ExecutorService jobWorker) {
        this.scheduler = jobScheduler;
        this.worker = jobWorker;
    }

    public record JobInfo(String id, String name, boolean cancelled) {}

    private static final class JobState {
        final String id;
        final ReschedulableJob job;
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<ScheduledFuture<?>> future = new AtomicReference<>();
        final AtomicReference<Future<?>> workerFuture = new AtomicReference<>();
        JobState(String id, ReschedulableJob job) { this.id = id; this.job = job; }
    }

    private final Map<String, JobState> jobs = new ConcurrentHashMap<>();

    /** Start een job met initiële delay. Geeft jobId terug. */
    public void schedule(ReschedulableJob job, Duration initialDelay) {
        var state = new JobState(job.getId(), job);
        var previous = jobs.put(job.getId(), state);
        if (previous != null) previous.cancelled.set(true); // vervangende job met zelfde id moet ook echt stoppen
        scheduleOnce(state, initialDelay);
    }

    public void stopAll() {
        jobs.keySet().forEach(jobId -> stop(jobId, true));
        this.scheduler.shutdown();
        this.worker.shutdownNow();
    }

    /**
     * Zacht stoppen: voorkomt volgende runs (lopende run mag afmaken).
     * Hard stoppen (force): onderbreekt ook de lopende run via een interrupt — gebruik dit
     * wanneer de job vervangen wordt door een nieuwe met hetzelfde id, anders publiceert de
     * oude run nog één keer verouderde content nadat de nieuwe al actief is.
     */
    public boolean stop(String jobId, boolean force) {
        var state = jobs.get(jobId);
        if (state == null) return false;
        state.cancelled.set(true);
        var f = state.future.getAndSet(null);
        if (f != null) f.cancel(force); // hard-stop: zet op true en zorg dat je job interrupt-vriendelijk is
        if (force) {
            var w = state.workerFuture.getAndSet(null);
            if (w != null) w.cancel(true); // onderbreek de lopende run op de worker-thread
        }
        jobs.remove(jobId, state); // alleen verwijderen als deze state er nog in staat: voorkomt het wegvegen van een vervangende job met hetzelfde id
        return true;
    }

    public Optional<JobInfo> get(String jobId) {
        var st = jobs.get(jobId);
        return Optional.ofNullable(st).map(s -> new JobInfo(s.id, s.job.name(), s.cancelled.get()));
    }

    public Map<String, JobInfo> list() {
        return jobs.values().stream().collect(
                java.util.stream.Collectors.toMap(js -> js.id, js -> new JobInfo(js.id, js.job.name(), js.cancelled.get()))
        );
    }

    // ---- intern

    private void scheduleOnce(JobState state, Duration delay) {
        if (state.cancelled.get()) return;
        var future = scheduler.schedule(() -> runAndMaybeReschedule(state), Instant.now().plus(delay));
        state.future.set(future);
    }

    private void runAndMaybeReschedule(JobState state) {
        if (state.cancelled.get()) return;

        //log.info("Scheduling v-thread for job {}", state.job.getId());
        var running = worker.submit(() -> {
            try (var _1 = MDC.putCloseable("jobId", state.id);
                 var _2 = MDC.putCloseable("job", state.job.name())) {

                try {
                    var next = state.job.run();

                    if (!state.cancelled.get() && next.isPresent()) {
                        var base = next.get();
                        int jitterMs = ThreadLocalRandom.current().nextInt(0, 250);
                        scheduleOnce(state, base.plusMillis(jitterMs));
                    } else {
                        jobs.remove(state.id, state); // identity-check: deze key kan inmiddels aan een vervangende job toebehoren
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    log.warn("Job interrupted; stopping.");
                    jobs.remove(state.id, state);
                } catch (Throwable t) {
                    log.error("Job run failed.", t);           // <— zichtbaar in je logs
                    if (!state.cancelled.get()) {
                        scheduleOnce(state, Duration.ofSeconds(30));
                    }
                }
            }
        });
        state.workerFuture.set(running);
        if (state.cancelled.get()) running.cancel(true); // stop() die net vóór deze set plaatsvond mag de run niet missen
    }
}
