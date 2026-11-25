package com.jobscheduler.common.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JobTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:02:30Z");
    private static final UUID OWNER = UUID.randomUUID();

    private static Job cronJob(String cron, Duration timeout) {
        return Job.create(OWNER, "job", Language.PYTHON, Schedule.cron(cron, "UTC"), timeout, NOW);
    }

    @Test
    void createComputesFirstRunAtLeastOneMinuteAhead() {
        Job job = cronJob("* * * * *", Duration.ofSeconds(30));
        // 10:03:00 is only 30s away, so the first run is 10:04:00.
        assertThat(job.getNextRunTime()).isEqualTo(Instant.parse("2026-11-21T10:04:00Z"));
        assertThat(job.isEnabled()).isTrue();
        assertThat(job.getScriptVersion()).isEqualTo(1);
    }

    @Test
    void timeoutMustBePositive() {
        assertThatThrownBy(() -> cronJob("* * * * *", Duration.ZERO)).isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    void timeoutMustNotExceedTheShortestInterval() {
        assertThatThrownBy(() -> cronJob("*/5 * * * *", Duration.ofMinutes(6)))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("interval");
        assertThat(cronJob("*/5 * * * *", Duration.ofMinutes(5)).timeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void timeoutHasAnUpperBound() {
        assertThatThrownBy(() -> cronJob("0 0 * * *", Duration.ofHours(2))).isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    void oneTimeRunMustBeAtLeastOneMinuteAhead() {
        Schedule tooSoon = Schedule.oneTime(NOW.plusSeconds(30), "UTC");
        assertThatThrownBy(() -> Job.create(OWNER, "j", Language.PYTHON, tooSoon, Duration.ofSeconds(10), NOW))
                .isInstanceOf(InvalidScheduleException.class);

        Schedule ok = Schedule.oneTime(NOW.plusSeconds(120), "UTC");
        Job job = Job.create(OWNER, "j", Language.PYTHON, ok, Duration.ofHours(1), NOW);
        assertThat(job.getNextRunTime()).isEqualTo(NOW.plusSeconds(120));
    }

    @Test
    void disableClearsNextRunAndEnableRecomputesIt() {
        Job job = cronJob("* * * * *", Duration.ofSeconds(30));
        job.disable(NOW);
        assertThat(job.isEnabled()).isFalse();
        assertThat(job.getNextRunTime()).isNull();

        Instant later = Instant.parse("2026-11-21T11:00:10Z");
        job.enable(later);
        assertThat(job.getNextRunTime()).isEqualTo(Instant.parse("2026-11-21T11:02:00Z"));
    }

    @Test
    void enablingAOneTimeJobWhoseTimePassedIsRejected() {
        Job job = Job.create(OWNER, "j", Language.PYTHON, Schedule.oneTime(NOW.plusSeconds(120), "UTC"),
                Duration.ofSeconds(10), NOW);
        job.disable(NOW);
        assertThatThrownBy(() -> job.enable(NOW.plusSeconds(600))).isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    void advanceScheduleMovesToNextOccurrenceOrNullForOneTime() {
        Job cron = cronJob("*/5 * * * *", Duration.ofSeconds(30));
        cron.advanceSchedule(Instant.parse("2026-11-21T10:05:00Z"));
        assertThat(cron.getNextRunTime()).isEqualTo(Instant.parse("2026-11-21T10:10:00Z"));

        Instant runAt = NOW.plusSeconds(120);
        Job once = Job.create(OWNER, "j", Language.PYTHON, Schedule.oneTime(runAt, "UTC"), Duration.ofSeconds(10), NOW);
        once.advanceSchedule(runAt);
        assertThat(once.getNextRunTime()).isNull();
    }

    @Test
    void runsSnapshotScriptVersionAndTimeout() {
        Job job = cronJob("*/5 * * * *", Duration.ofSeconds(30));
        job.newScriptVersion();
        Run run = job.createScheduledRun(Instant.parse("2026-11-21T10:05:00Z"), NOW);
        assertThat(run.getScriptVersion()).isEqualTo(2);
        assertThat(run.timeout()).isEqualTo(Duration.ofSeconds(30));
        assertThat(run.getTriggerType()).isEqualTo(TriggerType.SCHEDULED);
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);

        // Later edits do not change an existing run.
        job.updateSchedule(Schedule.cron("*/10 * * * *", "UTC"), Duration.ofSeconds(60), NOW);
        job.newScriptVersion();
        assertThat(run.getScriptVersion()).isEqualTo(2);
        assertThat(run.timeout()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void deleteIsSoftAndStopsScheduling() {
        Job job = cronJob("* * * * *", Duration.ofSeconds(30));
        job.delete(NOW);
        assertThat(job.isDeleted()).isTrue();
        assertThat(job.isEnabled()).isFalse();
        assertThat(job.getNextRunTime()).isNull();
        assertThatThrownBy(() -> job.createManualRun(NOW)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void ownership() {
        Job job = cronJob("* * * * *", Duration.ofSeconds(30));
        assertThat(job.isOwnedBy(OWNER)).isTrue();
        assertThat(job.isOwnedBy(UUID.randomUUID())).isFalse();
    }
}
