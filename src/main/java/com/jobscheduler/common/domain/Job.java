package com.jobscheduler.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Aggregate root. Owns its {@link Schedule} (embedded columns) and is the only creator of {@link Run}s.
 * All scheduling rules live here; services only load, call these methods and save.
 */
@Entity
@Table(name = "jobs")
public class Job {

    /** The first run must be at least this far ahead, so it never falls in a window the scheduler already processed. */
    public static final Duration MIN_LEAD_TIME = Duration.ofMinutes(1);
    /** Upper bound on timeout. The RabbitMQ consumer timeout is configured above this. */
    public static final Duration MAX_TIMEOUT = Duration.ofHours(1);

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Language language;

    @Column(name = "script_version", nullable = false)
    private int scriptVersion;

    @Embedded
    private Schedule schedule;

    @Column(name = "timeout_seconds", nullable = false)
    private int timeoutSeconds;

    @Column(nullable = false)
    private boolean enabled;

    @Column(name = "next_run_time")
    private Instant nextRunTime;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    protected Job() {
        // JPA
    }

    /** Factory method: a new, enabled job at script version 1 with its first run computed. */
    public static Job create(UUID ownerId, String name, Language language, Schedule schedule,
                             Duration timeout, Instant now) {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(schedule, "schedule");
        validate(schedule, timeout, now);

        Job job = new Job();
        job.id = UUID.randomUUID();
        job.ownerId = ownerId;
        job.name = name;
        job.language = language;
        job.scriptVersion = 1;
        job.schedule = schedule;
        job.timeoutSeconds = (int) timeout.toSeconds();
        job.enabled = true;
        job.createdAt = now;
        job.updatedAt = now;
        job.nextRunTime = job.firstRunFrom(now);
        return job;
    }

    /** Full replace of the editable fields (PUT). Script versioning is separate: see {@link #newScriptVersion}. */
    public void update(String name, Schedule schedule, Duration timeout, Instant now) {
        Objects.requireNonNull(name, "name");
        this.name = name;
        updateSchedule(schedule, timeout, now);
    }

    public void updateSchedule(Schedule schedule, Duration timeout, Instant now) {
        Objects.requireNonNull(schedule, "schedule");
        ensureNotDeleted();
        validate(schedule, timeout, now);
        this.schedule = schedule;
        this.timeoutSeconds = (int) timeout.toSeconds();
        this.updatedAt = now;
        this.nextRunTime = enabled ? firstRunFrom(now) : null;
    }

    /** Bumps and returns the script version; the caller uploads the script under the returned number. */
    public int newScriptVersion() {
        ensureNotDeleted();
        scriptVersion++;
        return scriptVersion;
    }

    public void enable(Instant now) {
        ensureNotDeleted();
        this.enabled = true;
        this.nextRunTime = firstRunFrom(now);
        this.updatedAt = now;
    }

    public void disable(Instant now) {
        ensureNotDeleted();
        this.enabled = false;
        this.nextRunTime = null;
        this.updatedAt = now;
    }

    /**
     * Moves nextRunTime to the first occurrence strictly after {@code after};
     * null when a one-time job has nothing left to run.
     */
    public void advanceSchedule(Instant after) {
        this.nextRunTime = schedule.nextRunAfter(after).orElse(null);
    }

    public void delete(Instant now) {
        if (deletedAt != null) {
            return;
        }
        this.deletedAt = now;
        this.enabled = false;
        this.nextRunTime = null;
        this.updatedAt = now;
    }

    public boolean isOwnedBy(UUID userId) {
        return ownerId.equals(userId);
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }

    /** Factory method for runs created by the scheduler. Snapshots script version and timeout. */
    public Run createScheduledRun(Instant scheduledTime, Instant now) {
        return Run.create(id, TriggerType.SCHEDULED, scheduledTime, scriptVersion, timeout(), now);
    }

    /** Factory method for "run now". The trigger time doubles as the scheduled time. */
    public Run createManualRun(Instant now) {
        ensureNotDeleted();
        return Run.create(id, TriggerType.MANUAL, now, scriptVersion, timeout(), now);
    }

    // ---- rules ----

    private static void validate(Schedule schedule, Duration timeout, Instant now) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new InvalidScheduleException("timeout must be greater than 0 seconds");
        }
        if (timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new InvalidScheduleException("timeout must be at most " + MAX_TIMEOUT.toSeconds() + " seconds");
        }
        if (schedule.type() == ScheduleType.ONE_TIME) {
            if (schedule.runAt().isBefore(now.plus(MIN_LEAD_TIME))) {
                throw new InvalidScheduleException("runAt must be at least 1 minute in the future");
            }
            return;
        }
        Duration interval = schedule.minInterval(now)
                .orElseThrow(() -> new InvalidScheduleException("cron expression has no upcoming occurrences"));
        if (timeout.compareTo(interval) > 0) {
            throw new InvalidScheduleException("timeout (" + timeout.toSeconds() + "s) must not exceed the shortest "
                    + "schedule interval (" + interval.toSeconds() + "s), so scheduled runs never overlap");
        }
    }

    /** First occurrence at least MIN_LEAD_TIME ahead of now. */
    private Instant firstRunFrom(Instant now) {
        Instant earliest = now.plus(MIN_LEAD_TIME);
        if (schedule.type() == ScheduleType.ONE_TIME) {
            if (schedule.runAt().isBefore(earliest)) {
                throw new InvalidScheduleException(
                        "this one-time job's runAt has passed; update the schedule before enabling it");
            }
            return schedule.runAt();
        }
        return schedule.nextRunAfter(earliest.minusNanos(1))
                .orElseThrow(() -> new InvalidScheduleException("cron expression has no upcoming occurrences"));
    }

    private void ensureNotDeleted() {
        if (deletedAt != null) {
            throw new IllegalStateException("job " + id + " is deleted");
        }
    }

    // ---- read access (no public setters) ----

    public UUID getId() { return id; }
    public UUID getOwnerId() { return ownerId; }
    public String getName() { return name; }
    public Language getLanguage() { return language; }
    public int getScriptVersion() { return scriptVersion; }
    public Schedule getSchedule() { return schedule; }
    public Duration timeout() { return Duration.ofSeconds(timeoutSeconds); }
    public boolean isEnabled() { return enabled; }
    public Instant getNextRunTime() { return nextRunTime; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
