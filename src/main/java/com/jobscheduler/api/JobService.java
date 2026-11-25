package com.jobscheduler.api;

import com.jobscheduler.api.dto.ApiDtos.JobRequest;
import com.jobscheduler.api.dto.ApiDtos.JobResponse;
import com.jobscheduler.api.dto.ApiDtos.PageResponse;
import com.jobscheduler.api.dto.ApiDtos.ScheduleRequest;
import com.jobscheduler.api.web.ApiExceptions.DuplicateJobNameException;
import com.jobscheduler.api.web.ApiExceptions.JobNotFoundException;
import com.jobscheduler.common.domain.FailureReason;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.Schedule;
import com.jobscheduler.common.repository.JobRepository;
import com.jobscheduler.common.repository.RunRepository;
import com.jobscheduler.common.storage.ScriptStorage;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Orchestration only: load, call domain methods, save, call storage. The rules are in {@link Job}. */
@Service
@Profile("api")
public class JobService {

    private static final String NAME_INDEX = "ux_jobs_owner_name";

    private final JobRepository jobs;
    private final RunRepository runs;
    private final ScriptStorage scripts;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final Clock clock;

    public JobService(JobRepository jobs, RunRepository runs, ScriptStorage scripts,
                      TransactionTemplate tx, Clock clock) {
        this.jobs = jobs;
        this.runs = runs;
        this.scripts = scripts;
        this.tx = tx;
        this.readOnlyTx = new TransactionTemplate(tx.getTransactionManager());
        this.readOnlyTx.setReadOnly(true);
        this.clock = clock;
    }

    /**
     * POST /jobs. The domain validates first (bad cron, timeout > interval, first run too soon),
     * then the script is uploaded as v1, then the job row is inserted. If the insert fails the
     * uploaded object is an orphan nobody references; accepted (storage and DB writes are not atomic).
     */
    public JobResponse create(UUID ownerId, JobRequest req) {
        Instant now = clock.instant();
        Job job = Job.create(ownerId, req.name().trim(), req.languageOrDefault(), toSchedule(req.schedule()),
                Duration.ofSeconds(req.timeoutSeconds()), now);
        if (jobs.existsByOwnerIdAndNameAndDeletedAtIsNull(ownerId, job.getName())) {
            throw new DuplicateJobNameException(job.getName());
        }
        scripts.upload(job.getId(), job.getScriptVersion(), job.getLanguage(), scriptBytes(req));
        try {
            tx.executeWithoutResult(s -> jobs.insert(job));
        } catch (DataIntegrityViolationException e) {
            throw translateNameClash(e, job.getName());
        }
        return JobResponse.from(job, null);
    }

    public PageResponse<JobResponse> list(UUID ownerId, Pageable pageable) {
        return readOnlyTx.execute(s -> PageResponse.from(
                jobs.findByOwnerIdAndDeletedAtIsNull(ownerId, pageable), job -> JobResponse.from(job, null)));
    }

    public JobResponse get(UUID ownerId, UUID jobId) {
        return readOnlyTx.execute(s -> {
            Job job = jobs.findByIdAndOwnerIdAndDeletedAtIsNull(jobId, ownerId).orElseThrow(JobNotFoundException::new);
            return JobResponse.from(job, runs.findFirstByJobIdOrderByScheduledTimeDesc(jobId).orElse(null));
        });
    }

    /**
     * PUT /jobs/{id}: full replace; every PUT stores the script as a new immutable version.
     *
     * <p>Deliberate exception to "no network calls inside a transaction": the upload happens while the
     * job row is locked. That serializes concurrent edits of the same job, so two PUTs can never both
     * write "v{n+1}" and leave a committed version pointing at the other request's script. It is one
     * rare API call holding one row; the scheduler's SKIP LOCKED simply skips this job for that moment.
     */
    public JobResponse update(UUID ownerId, UUID jobId, JobRequest req) {
        Schedule schedule = toSchedule(req.schedule());
        String name = req.name().trim();
        byte[] script = scriptBytes(req);
        try {
            return tx.execute(s -> {
                Instant now = clock.instant();
                Job job = lockOwned(ownerId, jobId);
                if (jobs.existsByOwnerIdAndNameAndDeletedAtIsNullAndIdNot(ownerId, name, jobId)) {
                    throw new DuplicateJobNameException(name);
                }
                job.update(name, schedule, Duration.ofSeconds(req.timeoutSeconds()), now); // validates before upload
                int version = job.newScriptVersion();
                scripts.upload(job.getId(), version, job.getLanguage(), script);
                return JobResponse.from(job, null);
            });
        } catch (DataIntegrityViolationException e) {
            throw translateNameClash(e, name);
        }
    }

    /**
     * PATCH /jobs/{id} {"enabled": ...}. Disabling also cancels this job's scheduled runs that are
     * still waiting (FAILED, JOB_DISABLED); a manual run already requested still goes ahead.
     */
    public JobResponse setEnabled(UUID ownerId, UUID jobId, boolean enabled) {
        return tx.execute(s -> {
            Instant now = clock.instant();
            Job job = lockOwned(ownerId, jobId);
            if (enabled) {
                job.enable(now);
            } else {
                job.disable(now);
                cancelQueuedRuns(jobId, true, "job was disabled", now);
            }
            return JobResponse.from(job, null);
        });
    }

    /** DELETE /jobs/{id}: soft delete; every waiting run of the job is cancelled. */
    public void delete(UUID ownerId, UUID jobId) {
        tx.executeWithoutResult(s -> {
            Instant now = clock.instant();
            Job job = lockOwned(ownerId, jobId);
            job.delete(now);
            cancelQueuedRuns(jobId, false, "job was deleted", now);
        });
    }

    // Lock order jobs -> runs, as everywhere else.
    private void cancelQueuedRuns(UUID jobId, boolean scheduledOnly, String detail, Instant now) {
        runs.lockQueuedRunsOfJob(jobId, scheduledOnly)
                .forEach(run -> run.markFailed(FailureReason.JOB_DISABLED, detail, now));
    }

    private Job lockOwned(UUID ownerId, UUID jobId) {
        return jobs.lockById(jobId)
                .filter(job -> job.isOwnedBy(ownerId) && !job.isDeleted())
                .orElseThrow(JobNotFoundException::new);
    }

    private static Schedule toSchedule(ScheduleRequest req) {
        return switch (req.type()) {
            case CRON -> Schedule.cron(req.cron(), req.timezone());
            case ONE_TIME -> Schedule.oneTime(req.runAt(), req.timezone());
        };
    }

    private static byte[] scriptBytes(JobRequest req) {
        return req.script().getBytes(StandardCharsets.UTF_8);
    }

    private static RuntimeException translateNameClash(DataIntegrityViolationException e, String name) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        return message.contains(NAME_INDEX) ? new DuplicateJobNameException(name) : e;
    }
}
