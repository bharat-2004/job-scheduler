package com.jobscheduler.api;

import com.jobscheduler.api.dto.ApiDtos.PageResponse;
import com.jobscheduler.api.dto.ApiDtos.RunResponse;
import com.jobscheduler.api.web.ApiExceptions.JobAlreadyRunningException;
import com.jobscheduler.api.web.ApiExceptions.JobNotFoundException;
import com.jobscheduler.api.web.ApiExceptions.LogNotFoundException;
import com.jobscheduler.api.web.ApiExceptions.RunNotFoundException;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.domain.RunId;
import com.jobscheduler.common.messaging.RunPublisher;
import com.jobscheduler.common.repository.JobRepository;
import com.jobscheduler.common.repository.RunRepository;
import com.jobscheduler.common.storage.LogStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Profile("api")
public class RunService {

    private static final Logger log = LoggerFactory.getLogger(RunService.class);

    private final JobRepository jobs;
    private final RunRepository runs;
    private final RunPublisher publisher;
    private final LogStorage logs;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final Clock clock;

    public RunService(JobRepository jobs, RunRepository runs, RunPublisher publisher, LogStorage logs,
                      TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.runs = runs;
        this.publisher = publisher;
        this.logs = logs;
        this.tx = tx;
        this.readOnlyTx = new TransactionTemplate(tx.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * POST /jobs/{id}/runs (manual trigger).
     * <ol>
     *   <li>Lock the job row: two simultaneous triggers serialize here, so the 409 check is race-free.</li>
     *   <li>409 if a run is RUNNING or a manual run is already QUEUED. A scheduled run waiting for its
     *       time does not block; if both end up due together, the worker fails the later one with
     *       PREVIOUS_RUN_IN_PROGRESS.</li>
     *   <li>Insert the run as QUEUED and commit. Only then publish (fast path) and mark it dispatched.
     *       If the publish fails, the scheduler's 10-second dispatcher publishes it instead.</li>
     * </ol>
     */
    public RunResponse triggerManual(UUID ownerId, UUID jobId) {
        Run run = tx.execute(s -> {
            Job job = jobs.lockById(jobId)
                    .filter(j -> j.isOwnedBy(ownerId) && !j.isDeleted())
                    .orElseThrow(JobNotFoundException::new);
            if (runs.blocksManualTrigger(jobId)) {
                throw new JobAlreadyRunningException();
            }
            return runs.insert(job.createManualRun(clock.instant()));
        });

        try {
            publisher.publishAll(List.of(new RunPublisher.Delivery(run.getId(), run.getJobId(), run.getScheduledTime())));
            Instant now = clock.instant();
            tx.executeWithoutResult(s -> runs.markDispatchedIfPending(run.getId(), run.getScheduledTime(), now));
        } catch (RuntimeException e) {
            log.warn("fast-path publish of manual run {} failed; the dispatcher will publish it: {}",
                    run.getId(), e.toString());
        }
        // Re-read (by primary key, one partition): dispatchedAt, or a worker may already have started it.
        return readOnlyTx.execute(s -> runs.findById(new RunId(run.getId(), run.getScheduledTime()))
                .map(RunResponse::from).orElseThrow());
    }

    public PageResponse<RunResponse> history(UUID ownerId, UUID jobId, Pageable pageable) {
        return readOnlyTx.execute(s -> {
            jobs.findByIdAndOwnerIdAndDeletedAtIsNull(jobId, ownerId).orElseThrow(JobNotFoundException::new);
            return PageResponse.from(runs.findByJobIdOrderByScheduledTimeDesc(jobId, pageable), RunResponse::from);
        });
    }

    public RunResponse get(UUID ownerId, UUID runId) {
        return readOnlyTx.execute(s -> RunResponse.from(ownedRun(ownerId, runId)));
    }

    /** The caller must close the stream. */
    public InputStream openLog(UUID ownerId, UUID runId) {
        Run run = readOnlyTx.execute(s -> ownedRun(ownerId, runId));
        if (run.getLogPath() == null) {
            throw new LogNotFoundException("no logs for this run (status " + run.getStatus() + ")");
        }
        return logs.open(run.getLogPath())
                .orElseThrow(() -> new LogNotFoundException("logs for this run have expired"));
    }

    private Run ownedRun(UUID ownerId, UUID runId) {
        return runs.findOwned(runId, ownerId).orElseThrow(RunNotFoundException::new);
    }
}
