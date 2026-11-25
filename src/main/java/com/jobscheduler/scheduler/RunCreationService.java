package com.jobscheduler.scheduler;

import com.jobscheduler.common.config.RetentionProperties;
import com.jobscheduler.common.domain.FailureReason;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.repository.JobRepository;
import com.jobscheduler.common.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Step 1 of the minute flow: turn due jobs into QUEUED runs. Writes only to the database
 * (the dispatcher publishes later), so a crash at any point loses nothing.
 */
@Service
@Profile("scheduler")
public class RunCreationService {

    private static final Logger log = LoggerFactory.getLogger(RunCreationService.class);

    private final JobRepository jobs;
    private final RunRepository runs;
    private final TransactionTemplate tx;
    private final SchedulerProperties props;
    private final RetentionProperties retention;
    private final Clock clock;

    public RunCreationService(JobRepository jobs, RunRepository runs, TransactionTemplate tx,
                              SchedulerProperties props, RetentionProperties retention, Clock clock) {
        this.jobs = jobs;
        this.runs = runs;
        this.tx = tx;
        this.props = props;
        this.retention = retention;
        this.clock = clock;
    }

    /**
     * Creates runs for every job due before {@code horizon}, in batches, each batch its own short
     * transaction. The ticker passes a horizon one minute ahead, so runs are created about a minute
     * before their time and RabbitMQ's delayed delivery releases them on time. Loops until a batch finds
     * nothing: a late job advanced to an occurrence before the horizon is picked up by the next batch.
     */
    public int createDueRuns(Instant horizon) {
        int total = 0;
        while (true) {
            Integer processed;
            try {
                processed = tx.execute(status -> createBatch(horizon));
            } catch (RuntimeException e) {
                // Rolled back as a whole; the jobs are still due and the next tick retries them.
                log.error("run creation batch failed; retrying next tick", e);
                break;
            }
            if (processed == null || processed == 0) {
                break;
            }
            total += processed;
        }
        if (total > 0) {
            log.info("runs due before {}: processed {} jobs", horizon, total);
        }
        return total;
    }

    private int createBatch(Instant horizon) {
        Instant now = clock.instant();
        Instant lateLimit = now.minus(props.lateGrace());
        Instant retentionLimit = now.minus(Duration.ofDays(retention.days()));

        List<Job> due = jobs.lockDueJobs(horizon, props.batchSize());
        if (due.isEmpty()) {
            return 0;
        }
        // App-level duplicate check (e.g. a one-time job re-saved with the same runAt after its run was
        // created), one query for the whole batch; the partial unique index is the backstop.
        Set<Map.Entry<UUID, Instant>> alreadyCreated = runs.findScheduledRuns(
                        due.stream().map(Job::getId).toList(),
                        due.stream().map(Job::getNextRunTime).collect(Collectors.toSet()))
                .stream()
                .map(r -> Map.entry(r.getJobId(), r.getScheduledTime()))
                .collect(Collectors.toSet());

        for (Job job : due) {
            MDC.put("jobId", job.getId().toString());
            try {
                Instant scheduledTime = job.getNextRunTime();

                if (alreadyCreated.contains(Map.entry(job.getId(), scheduledTime))) {
                    log.warn("run for {} at {} already exists; only advancing the schedule", job.getId(), scheduledTime);
                } else if (!scheduledTime.isBefore(lateLimit)) {
                    // Future (normal, published with a delay) or only slightly late (e.g. its row was locked
                    // during the previous tick): created normally; the worker's delay alert covers lateness.
                    runs.insert(job.createScheduledRun(scheduledTime, now));
                } else if (scheduledTime.isAfter(retentionLimit)) {
                    // Missed while the system was down: record it once, no catch-up.
                    Run missed = job.createScheduledRun(scheduledTime, now);
                    missed.markFailed(FailureReason.SYSTEM_DOWN,
                            "scheduler was not running at the scheduled time", now);
                    runs.insert(missed);
                    log.warn("job {} missed its run at {} (system down)", job.getId(), scheduledTime);
                }

                // A past occurrence (late or missed) jumps to the next future one: no catch-up runs.
                job.advanceSchedule(scheduledTime.isBefore(now) ? now : scheduledTime);
            } finally {
                MDC.remove("jobId");
            }
        }
        return due.size();
    }
}
