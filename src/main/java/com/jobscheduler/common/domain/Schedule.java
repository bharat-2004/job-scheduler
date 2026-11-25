package com.jobscheduler.common.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import org.springframework.scheduling.support.CronExpression;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Value object: when a job runs. Immutable; built only through {@link #cron} or {@link #oneTime},
 * both of which reject invalid input, so a Schedule that exists is always valid.
 *
 * <p>Cron expressions use the standard 5 fields (minute hour day-of-month month day-of-week)
 * and are evaluated in the job's timezone; every returned instant is UTC.
 */
@Embeddable
public class Schedule {

    /** How far ahead minInterval() looks. Covers DST shifts and month-length differences. */
    private static final int MAX_SAMPLES = 1000;
    private static final Duration SAMPLE_HORIZON = Duration.ofDays(366);

    @Enumerated(EnumType.STRING)
    @Column(name = "schedule_type", nullable = false, length = 10)
    private ScheduleType type;

    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    @Column(name = "run_at")
    private Instant runAt;

    @Column(name = "timezone", nullable = false, length = 50)
    private String timezone;

    protected Schedule() {
        // JPA
    }

    private Schedule(ScheduleType type, String cronExpression, Instant runAt, ZoneId zone) {
        this.type = type;
        this.cronExpression = cronExpression;
        this.runAt = runAt;
        this.timezone = zone.getId();
    }

    public static Schedule cron(String expression, String timezone) {
        if (expression == null || expression.isBlank()) {
            throw new InvalidScheduleException("cron expression is required for a CRON schedule");
        }
        String normalized = expression.trim().replaceAll("\\s+", " ");
        parseCron(normalized);
        return new Schedule(ScheduleType.CRON, normalized, null, parseZone(timezone));
    }

    public static Schedule oneTime(Instant runAt, String timezone) {
        if (runAt == null) {
            throw new InvalidScheduleException("runAt is required for a ONE_TIME schedule");
        }
        return new Schedule(ScheduleType.ONE_TIME, null, runAt, parseZone(timezone));
    }

    /** The first occurrence strictly after {@code after}, or empty if there is none (a past one-time run). */
    public Optional<Instant> nextRunAfter(Instant after) {
        if (type == ScheduleType.ONE_TIME) {
            return runAt.isAfter(after) ? Optional.of(runAt) : Optional.empty();
        }
        ZonedDateTime next = parseCron(cronExpression).next(after.atZone(zone()));
        return Optional.ofNullable(next).map(ZonedDateTime::toInstant);
    }

    /**
     * Shortest gap between consecutive occurrences, sampled over the next year from {@code from}.
     * Sampling is needed because cron gaps vary: "0 9 * * 1-5" has 24h and 72h gaps, and DST
     * can turn a daily job's 24h gap into 23h. One-time schedules have no interval.
     */
    public Optional<Duration> minInterval(Instant from) {
        if (type == ScheduleType.ONE_TIME) {
            return Optional.empty();
        }
        CronExpression cron = parseCron(cronExpression);
        ZonedDateTime horizon = from.plus(SAMPLE_HORIZON).atZone(zone());
        ZonedDateTime previous = cron.next(from.atZone(zone()));
        Duration min = null;
        for (int i = 0; i < MAX_SAMPLES && previous != null && previous.isBefore(horizon); i++) {
            ZonedDateTime next = cron.next(previous);
            if (next == null) {
                break;
            }
            Duration gap = Duration.between(previous.toInstant(), next.toInstant());
            if (min == null || gap.compareTo(min) < 0) {
                min = gap;
            }
            previous = next;
        }
        return Optional.ofNullable(min);
    }

    private static CronExpression parseCron(String expression) {
        // Spring's CronExpression has a leading seconds field; users write standard 5-field cron.
        String springExpression = expression.startsWith("@") ? expression : "0 " + expression;
        if (!expression.startsWith("@") && expression.split(" ").length != 5) {
            throw new InvalidScheduleException(
                    "cron expression must have 5 fields (minute hour day-of-month month day-of-week): " + expression);
        }
        try {
            return CronExpression.parse(springExpression);
        } catch (IllegalArgumentException e) {
            throw new InvalidScheduleException("invalid cron expression '" + expression + "': " + e.getMessage());
        }
    }

    private static ZoneId parseZone(String timezone) {
        if (timezone == null || timezone.isBlank()) {
            return ZoneId.of("UTC");
        }
        try {
            return ZoneId.of(timezone.trim());
        } catch (DateTimeException e) {
            throw new InvalidScheduleException("unknown timezone: " + timezone);
        }
    }

    public ScheduleType type() {
        return type;
    }

    public String cronExpression() {
        return cronExpression;
    }

    public Instant runAt() {
        return runAt;
    }

    public ZoneId zone() {
        return ZoneId.of(timezone);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Schedule other)) return false;
        return type == other.type
                && Objects.equals(cronExpression, other.cronExpression)
                && Objects.equals(runAt, other.runAt)
                && Objects.equals(timezone, other.timezone);
    }

    @Override
    public int hashCode() {
        return Objects.hash(type, cronExpression, runAt, timezone);
    }

    @Override
    public String toString() {
        return type == ScheduleType.CRON
                ? "cron(" + cronExpression + " @ " + timezone + ")"
                : "oneTime(" + runAt + ")";
    }
}
