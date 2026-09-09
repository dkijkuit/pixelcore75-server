package nl.ctasoftware.crypto.ticker.server.service.job;

import org.jobrunr.jobs.Job;
import org.jobrunr.jobs.filters.JobServerFilter;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * Puts the panel {@code serial} and the JobRunr {@code jobId} on the MDC for every
 * rotation execution (replaces the old hand-rolled scheduler's MDC wiring).
 */
@Component
public class PanelJobMdcFilter implements JobServerFilter {

    @Override
    public void onProcessing(final Job job) {
        MDC.put("jobId", job.getId().toString());
        final var parameters = job.getJobDetails().getJobParameters();
        if (parameters != null && !parameters.isEmpty() && parameters.getFirst().getObject() instanceof String serial) {
            MDC.put("serial", serial);
        }
    }

    @Override
    public void onProcessingSucceeded(final Job job) {
        clear();
    }

    @Override
    public void onProcessingFailed(final Job job, final Exception e) {
        clear();
    }

    @Override
    public void onFailedAfterRetries(final Job job) {
        clear();
    }

    private static void clear() {
        MDC.remove("jobId");
        MDC.remove("serial");
    }
}
