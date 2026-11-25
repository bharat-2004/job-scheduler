package com.jobscheduler.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One execution of a job. Created only by {@link Job}; references it by id.
 * Every status change goes through a mark* method that enforces the state machine,
 * so a late or duplicate message can never change a finished run.
 */
@Entity
@Table(name = "runs")
@IdClass(RunId.class)
public class Run {

    private static final int MAX_DETAIL = 500;

    @Id
    private UUID id;

    @Id
    @Column(name = "scheduled_time", nullable = false)
    private Instant scheduledTime;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Enumerated(EnumType.STRING)
    @Column(name = "trigger_type", nullable = false, length = 10)
    private TriggerType triggerType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RunStatus status;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Column(name = "script_version", nullable = false)
    private int scriptVersion;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(name = "exit_code")
    private Integer exitCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private FailureReason failureReason;

    @Column(name = "failure_detail", length = MAX_DETAIL)
    private String failureDetail;

    @Column(name = "log_path", length = 500)
    private String logPath;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Run() {
        // JPA
    }

    /** Package-private: only Job creates runs. */
    static Run create(UUID jobId, TriggerType triggerType, Instant scheduledTime,
                      int scriptVersion, Duration timeout, Instant now) {
        Run run = new Run();
        run.id = UuidV7.generate(now);
        run.jobId = Objects.requireNonNull(jobId);
        run.triggerType = Objects.requireNonNull(triggerType);
        run.scheduledTime = Objects.requireNonNull(scheduledTime);
        run.status = RunStatus.QUEUED;
        run.scriptVersion = scriptVersion;
        run.timeoutSeconds = (int) timeout.toSeconds();
        run.createdAt = now;
        return run;
    }

    // ---- state machine ----

    /** Outbox marker: published to RabbitMQ. Status stays QUEUED. */
    public void markDispatched(Instant now) {
        if (status != RunStatus.QUEUED || dispatchedAt != null) {
            throw new IllegalRunTransitionException("run " + id + " cannot be dispatched from " + status
                    + (dispatchedAt != null ? " (already dispatched)" : ""));
        }
        this.dispatchedAt = now;
    }

    public void markRunning(String workerId, Instant now) {
        transitionTo(RunStatus.RUNNING);
        this.workerId = workerId;
        this.startedAt = now;
    }

    public void markSucceeded(int exitCode, String logPath, String note, Instant now) {
        transitionTo(RunStatus.SUCCESS);
        this.exitCode = exitCode;
        this.logPath = logPath;
        this.failureDetail = truncate(note);
        this.finishedAt = now;
    }

    public void markTimedOut(String logPath, String note, Instant now) {
        transitionTo(RunStatus.TIMED_OUT);
        this.logPath = logPath;
        this.failureDetail = truncate(note);
        this.finishedAt = now;
    }

    /** The script ran and exited non-zero. */
    public void markScriptError(int exitCode, String logPath, String detail, Instant now) {
        markFailed(FailureReason.SCRIPT_ERROR, detail, now);
        this.exitCode = exitCode;
        this.logPath = logPath;
    }

    public void markFailed(FailureReason reason, String detail, Instant now) {
        Objects.requireNonNull(reason, "reason");
        if (status != reason.allowedFrom()) {
            throw new IllegalRunTransitionException(
                    "run " + id + ": " + reason + " is only valid from " + reason.allowedFrom() + ", run is " + status);
        }
        transitionTo(RunStatus.FAILED);
        this.failureReason = reason;
        this.failureDetail = truncate(detail);
        this.finishedAt = now;
    }

    private void transitionTo(RunStatus target) {
        if (!status.canTransitionTo(target)) {
            throw new IllegalRunTransitionException("run " + id + " cannot move from " + status + " to " + target);
        }
        this.status = target;
    }

    /** How late the run started compared with when it should have. Zero if not started or early. */
    public Duration startDelay() {
        if (startedAt == null || startedAt.isBefore(scheduledTime)) {
            return Duration.ZERO;
        }
        return Duration.between(scheduledTime, startedAt);
    }

    public boolean isTerminal() {
        return status.isTerminal();
    }

    private static String truncate(String text) {
        if (text == null) return null;
        return text.length() <= MAX_DETAIL ? text : text.substring(0, MAX_DETAIL - 3) + "...";
    }

    // ---- read access ----

    public UUID getId() { return id; }
    public UUID getJobId() { return jobId; }
    public TriggerType getTriggerType() { return triggerType; }
    public RunStatus getStatus() { return status; }
    public Instant getScheduledTime() { return scheduledTime; }
    public Instant getDispatchedAt() { return dispatchedAt; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public int getScriptVersion() { return scriptVersion; }
    public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }
    public Integer getExitCode() { return exitCode; }
    public FailureReason getFailureReason() { return failureReason; }
    public String getFailureDetail() { return failureDetail; }
    public String getLogPath() { return logPath; }
    public String getWorkerId() { return workerId; }
    public Instant getCreatedAt() { return createdAt; }
}
