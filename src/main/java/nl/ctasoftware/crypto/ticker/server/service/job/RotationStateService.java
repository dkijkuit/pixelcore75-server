package nl.ctasoftware.crypto.ticker.server.service.job;

import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-serial transient rotation state, externalized from the old per-job instance so a
 * per-execution job model (JobRunr) works: the rotation index and the one-ahead staging
 * survive across job executions, the epoch supersedes stale preview/command threads
 * (replacing both the shared previewGenerations map and the force-stop interrupt), and
 * {@code allowedJobId} admits exactly the one job that may render next — a freshly minted
 * id per enqueue, so orphaned successors (server restart lost the delete, config save
 * raced a slot boundary) wake up, see a foreign id, and exit without rendering.
 *
 * <p>All admission changes ({@link #kick}, {@link #mintSuccessorJobId}, {@link #stoppedByDelete},
 * the successor commit) synchronize on the per-serial state object, so a slot-boundary commit
 * and a config-save kick serialize: exactly one of them decides who runs next.
 */
@Service
public class RotationStateService {

    /** Everything mutable a rotation needs between two job executions, per serial. */
    public static final class RotationState {
        final AtomicLong epoch = new AtomicLong();
        final AtomicReference<String> allowedJobId = new AtomicReference<>();
        final AtomicReference<Integer> rotationIndex = new AtomicReference<>(-1);
        volatile int stagedNextIdx = -1;
        volatile long stagedNextUploadId;
    }

    private final ConcurrentMap<String, RotationState> states = new ConcurrentHashMap<>();

    public RotationState stateFor(final String serial) {
        return states.computeIfAbsent(serial, k -> new RotationState());
    }

    /**
     * Admits a brand-new immediate run (config save, panel register, startup reconciliation)
     * and resets the rotation to its first screen: any in-flight run exits at its next safe
     * point and any pending successor job wakes up to a foreign id and dies.
     */
    public String kick(final String serial) {
        final RotationState state = stateFor(serial);
        synchronized (state) {
            final String jobId = UUID.randomUUID().toString();
            state.allowedJobId.set(jobId);
            state.rotationIndex.set(-1);
            clearStaged(state);
            state.epoch.incrementAndGet(); // kill the previous slot's preview/command threads
            return jobId;
        }
    }

    /**
     * The slot-boundary commit, under the same lock as {@link #kick}: the running job may
     * mint its successor only while it is still the admitted job. Returns the successor's
     * id, or null when a config save/delete superseded this run in the meantime.
     */
    public String mintSuccessorJobId(final String serial, final String runningJobId) {
        final RotationState state = stateFor(serial);
        synchronized (state) {
            if (!isAllowed(state, runningJobId)) {
                return null;
            }
            final String successorId = UUID.randomUUID().toString();
            state.allowedJobId.set(successorId);
            return successorId;
        }
    }

    /** Whether this exact job execution may render (called at the run's admission and safe points). */
    public boolean isAllowed(final String serial, final String jobId) {
        return isAllowed(stateFor(serial), jobId);
    }

    private static boolean isAllowed(final RotationState state, final String jobId) {
        return jobId != null && jobId.equals(state.allowedJobId.get());
    }

    /** Admits the allowed job's slot: bumps the epoch (superseding the previous slot's threads). */
    public long beginRun(final String serial) {
        final RotationState state = stateFor(serial);
        synchronized (state) {
            return state.epoch.incrementAndGet();
        }
    }

    public long currentEpoch(final String serial) {
        return stateFor(serial).epoch.get();
    }

    public void bumpEpoch(final String serial) {
        stateFor(serial).epoch.incrementAndGet();
    }

    /** Rotation index advance (externalized {@code screenIndex}). */
    public int nextScreenIndex(final String serial, final int screenCount) {
        final RotationState state = stateFor(serial);
        final int next = state.rotationIndex.updateAndGet(idx -> idx + 1 >= screenCount ? 0 : idx + 1);
        return Math.min(next, screenCount - 1);
    }

    /** Drops the per-serial state entirely (panel delete): stale executions exit everywhere. */
    public void evict(final String serial) {
        states.remove(serial);
    }

    /** Delete-path: invalidates everything while keeping a tombstone for waking stale jobs. */
    public void stoppedByDelete(final String serial) {
        final RotationState state = stateFor(serial);
        synchronized (state) {
            state.allowedJobId.set(null);
            clearStaged(state);
            state.epoch.incrementAndGet();
        }
    }

    public void clearStaged(final RotationState state) {
        state.stagedNextIdx = -1;
        state.stagedNextUploadId = 0;
    }

    public void setStaged(final RotationState state, final int idx, final long uploadId) {
        state.stagedNextIdx = idx;
        state.stagedNextUploadId = uploadId;
    }

    public boolean isStagedFor(final RotationState state, final int idx) {
        return state.stagedNextIdx == idx;
    }

    public long stagedUploadId(final RotationState state) {
        return state.stagedNextUploadId;
    }

    public void consumeStaged(final RotationState state) {
        state.stagedNextIdx = -1;
    }
}
