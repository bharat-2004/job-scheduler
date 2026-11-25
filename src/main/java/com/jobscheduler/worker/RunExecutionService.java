package com.jobscheduler.worker;

import com.jobscheduler.common.domain.FailureReason;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.Language;
import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.domain.RunStatus;
import com.jobscheduler.common.domain.TriggerType;
import com.jobscheduler.common.messaging.RunMessage;
import com.jobscheduler.common.notification.NotificationService;
import com.jobscheduler.common.repository.JobRepository;
import com.jobscheduler.common.repository.RunRepository;
import com.jobscheduler.common.repository.UserRepository;
import com.jobscheduler.common.storage.LogStorage;
import com.jobscheduler.common.storage.ScriptStorage;
import com.jobscheduler.worker.runtime.LanguageRuntime;
import com.jobscheduler.worker.runtime.LanguageRuntimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Turns one queue message into one finished run, in three phases. Only start and finish hold a
 * database transaction (milliseconds each); the script itself runs with no transaction and no
 * connection held, so thousands of concurrent runs do not exhaust the connection pool.
 *
 * <p>Error rules:
 * <ul>
 *   <li>failure before markRunning commits: the exception reaches the listener, the message is
 *       returned to the queue (with back-off) and retried; the run is still QUEUED, so that is safe</li>
 *   <li>failure after markRunning: never rethrown, the message is acked; if the finish phase could not
 *       record a result, the StuckRunSweeper marks the run FAILED after timeout + grace</li>
 * </ul>
 */
@Service
@Profile("worker")
public class RunExecutionService {

    private static final Logger log = LoggerFactory.getLogger(RunExecutionService.class);

    private final JobRepository jobs;
    private final RunRepository runs;
    private final UserRepository users;
    private final ScriptStorage scripts;
    private final LogStorage logStorage;
    private final ContainerRunner containerRunner;
    private final LanguageRuntimes runtimes;
    private final NotificationService notifications;
    private final TransactionTemplate tx;
    private final WorkerProperties props;
    private final Clock clock;
    private final String workerId;

    public RunExecutionService(JobRepository jobs, RunRepository runs, UserRepository users,
                               ScriptStorage scripts, LogStorage logStorage, ContainerRunner containerRunner,
                               LanguageRuntimes runtimes, NotificationService notifications,
                               TransactionTemplate tx, WorkerProperties props, Clock clock) {
        this.jobs = jobs;
        this.runs = runs;
        this.users = users;
        this.scripts = scripts;
        this.logStorage = logStorage;
        this.containerRunner = containerRunner;
        this.runtimes = runtimes;
        this.notifications = notifications;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
        // hostname + instance: a restarted process on the same host gets a new id.
        this.workerId = props.hostname() + "#" + Long.toHexString(ThreadLocalRandom.current().nextLong() & 0xFFFFFFL);
    }

    public void execute(RunMessage message) {
        MDC.put("runId", String.valueOf(message.runId()));
        MDC.put("jobId", String.valueOf(message.jobId()));
        try {
            StartedRun started = startPhase(message); // throws -> message returned and retried
            if (started == null) {
                return;
            }
            log.info("run started on {} (start delay {}s)", workerId, started.startDelay().toSeconds());
            sendDelayAlertIfLate(started);

            Path workDir = null;
            try {
                workDir = Files.createDirectories(Path.of(props.workDir(), started.runId().toString()));
                Outcome outcome = executePhase(started, workDir);
                finishPhase(started, outcome);
            } catch (Exception e) {
                // After markRunning: ack anyway. The sweeper closes the run if we could not.
                log.error("run failed after it was marked RUNNING; the sweeper will close it if needed", e);
            } finally {
                deleteQuietly(workDir);
            }
        } finally {
            MDC.remove("runId");
            MDC.remove("jobId");
        }
    }

    // ---- phase 1: start (short transaction, lock order jobs -> runs) ----

    private StartedRun startPhase(RunMessage message) {
        return tx.execute(status -> {
            Instant now = clock.instant();
            Job job = jobs.lockById(message.jobId()).orElse(null);
            Run run = runs.lockByRunId(message.runId()).orElse(null);

            if (run == null || (job != null && !run.getJobId().equals(job.getId()))) {
                log.warn("message for unknown run {}; ignoring", message.runId());
                return null;
            }
            if (run.getStatus() != RunStatus.QUEUED) {
                // Duplicate publish or redelivery after a crash: never start a run twice.
                log.info("run is already {}; ignoring duplicate message", run.getStatus());
                return null;
            }
            if (job == null || job.isDeleted()) {
                run.markFailed(FailureReason.JOB_DISABLED, "job was deleted before the run started", now);
                return null;
            }
            if (run.getTriggerType() == TriggerType.SCHEDULED && !job.isEnabled()) {
                run.markFailed(FailureReason.JOB_DISABLED, "job was disabled before the run started", now);
                return null;
            }
            if (runs.existsOtherRunning(job.getId(), run.getId())) {
                run.markFailed(FailureReason.PREVIOUS_RUN_IN_PROGRESS, "another run of this job was still running", now);
                log.info("previous run still in progress; run marked FAILED");
                return null;
            }
            run.markRunning(workerId, now);
            return new StartedRun(run.getId(), job.getId(), job.getName(), job.getOwnerId(), job.getLanguage(),
                    run.getScriptVersion(), run.timeout(), run.getScheduledTime(), now, run.startDelay());
        });
    }

    private void sendDelayAlertIfLate(StartedRun started) {
        if (started.startDelay().compareTo(props.delayAlertThreshold()) <= 0) {
            return;
        }
        try {
            users.findById(started.ownerId()).ifPresent(user -> notifications.sendDelayAlert(
                    new NotificationService.DelayAlert(user.getEmail(), started.jobId(), started.jobName(),
                            started.runId(), started.scheduledTime(), started.startedAt(), started.startDelay())));
        } catch (RuntimeException e) {
            log.warn("delay alert could not be sent (best effort): {}", e.toString());
        }
    }

    // ---- phase 2: execute (no transaction) ----

    private Outcome executePhase(StartedRun started, Path workDir) throws IOException {
        LanguageRuntime runtime = runtimes.forLanguage(started.language());

        byte[] script;
        try {
            script = scripts.fetch(started.jobId(), started.scriptVersion(), started.language());
        } catch (RuntimeException e) {
            return Outcome.infrastructure("could not fetch script v" + started.scriptVersion() + ": " + e.getMessage());
        }
        Path scriptFile = Files.write(workDir.resolve(runtime.scriptFileName()), script);
        Path logFile = workDir.resolve("run.log");

        try {
            ContainerRunner.Result result = containerRunner.run(
                    new ContainerRunner.Request(started.runId(), runtime, scriptFile, started.timeout(), logFile));
            return Outcome.completed(result);
        } catch (RuntimeException e) {
            log.error("container could not run", e);
            return Outcome.infrastructure("container error: " + e.getMessage());
        }
    }

    // ---- phase 3: finish (upload outside, then a short transaction) ----

    private void finishPhase(StartedRun started, Outcome outcome) {
        String logPath = null;
        String note = null;
        if (outcome.result() != null && Files.exists(outcome.result().logFile())) {
            try {
                logPath = logStorage.upload(started.jobId(), started.runId(), outcome.result().logFile());
            } catch (RuntimeException e) {
                note = "log upload failed: " + e.getMessage();
                log.warn(note);
            }
            if (outcome.result().logTruncated()) {
                note = join(note, "log truncated at " + props.logSizeCap());
            }
        }

        String finalLogPath = logPath;
        String finalNote = note;
        RunStatus finalStatus = tx.execute(status -> {
            Instant now = clock.instant();
            Run run = runs.lockByKey(started.runId(), started.scheduledTime()).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING) {
                // The sweeper got there first (we exceeded timeout + grace); keep its verdict.
                log.warn("run was already closed as {} before the worker finished", run.getStatus());
                return run.getStatus();
            }
            ContainerRunner.Result result = outcome.result();
            if (result == null) {
                run.markFailed(FailureReason.INFRASTRUCTURE_ERROR, outcome.detail(), now);
            } else if (result.timedOut()) {
                run.markTimedOut(finalLogPath, join("killed after " + started.timeout().toSeconds() + "s timeout", finalNote), now);
            } else if (result.exitCode() != null && result.exitCode() == 0) {
                run.markSucceeded(0, finalLogPath, finalNote, now);
            } else {
                String detail = result.oomKilled()
                        ? "killed: exceeded the memory limit (" + props.sandbox().memory() + ")"
                        : "script exited with code " + result.exitCode();
                run.markScriptError(result.exitCode() == null ? -1 : result.exitCode(), finalLogPath,
                        join(detail, finalNote), now);
            }
            return run.getStatus();
        });
        log.info("run finished: {}", finalStatus);
    }

    private static String join(String a, String b) {
        if (a == null) return b;
        if (b == null) return a;
        return a + "; " + b;
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) return;
        try {
            FileSystemUtils.deleteRecursively(dir);
        } catch (IOException e) {
            log.warn("could not delete {}: {}", dir, e.toString());
        }
    }

    /** What the start phase hands to the later phases (a snapshot; no entities outside transactions). */
    record StartedRun(UUID runId, UUID jobId, String jobName, UUID ownerId, Language language,
                      int scriptVersion, Duration timeout, Instant scheduledTime, Instant startedAt,
                      Duration startDelay) {
    }

    /** Either the container ran (result set) or infrastructure failed before it could (detail set). */
    record Outcome(ContainerRunner.Result result, String detail) {
        static Outcome completed(ContainerRunner.Result result) {
            return new Outcome(result, null);
        }

        static Outcome infrastructure(String detail) {
            return new Outcome(null, detail);
        }
    }
}
