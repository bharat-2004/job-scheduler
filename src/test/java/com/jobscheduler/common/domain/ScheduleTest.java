package com.jobscheduler.common.domain;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleTest {

    private static final Instant NOW = Instant.parse("2026-11-21T10:02:30Z");

    @Test
    void rejectsInvalidCron() {
        assertThatThrownBy(() -> Schedule.cron("not a cron", "UTC")).isInstanceOf(InvalidScheduleException.class);
        assertThatThrownBy(() -> Schedule.cron("61 * * * *", "UTC")).isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    void rejectsSixFieldCron() {
        // Users write standard 5-field cron; a seconds field would allow sub-minute schedules.
        assertThatThrownBy(() -> Schedule.cron("0 */5 * * * *", "UTC"))
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("5 fields");
    }

    @Test
    void rejectsUnknownTimezone() {
        assertThatThrownBy(() -> Schedule.cron("* * * * *", "Mars/Olympus")).isInstanceOf(InvalidScheduleException.class);
    }

    @Test
    void defaultsToUtc() {
        assertThat(Schedule.cron("* * * * *", null).zone().getId()).isEqualTo("UTC");
    }

    @Test
    void nextRunIsComputedInTheJobsTimezoneAndReturnedInUtc() {
        // 09:00 in Kolkata (UTC+5:30) is 03:30 UTC.
        Schedule daily = Schedule.cron("0 9 * * *", "Asia/Kolkata");
        assertThat(daily.nextRunAfter(NOW)).contains(Instant.parse("2026-11-22T03:30:00Z"));
    }

    @Test
    void nextRunAfterIsStrictlyAfter() {
        Schedule everyMinute = Schedule.cron("* * * * *", "UTC");
        Instant tenOhThree = Instant.parse("2026-11-21T10:03:00Z");
        assertThat(everyMinute.nextRunAfter(tenOhThree)).contains(Instant.parse("2026-11-21T10:04:00Z"));
        assertThat(everyMinute.nextRunAfter(tenOhThree.minusNanos(1))).contains(tenOhThree);
    }

    @Test
    void oneTimeHasNoRunAfterItsTime() {
        Instant runAt = Instant.parse("2026-11-21T12:00:00Z");
        Schedule once = Schedule.oneTime(runAt, "UTC");
        assertThat(once.nextRunAfter(NOW)).contains(runAt);
        assertThat(once.nextRunAfter(runAt)).isEmpty();
        assertThat(once.minInterval(NOW)).isEmpty();
    }

    @Test
    void minIntervalFindsTheShortestGapNotTheFirstOne() {
        // Weekdays at 09:00: gaps are 24h (Mon->Tue) and 72h (Fri->Mon). NOW is a Saturday.
        Schedule weekdays = Schedule.cron("0 9 * * 1-5", "UTC");
        assertThat(weekdays.minInterval(NOW)).contains(Duration.ofHours(24));

        // 1st and 2nd of the month: one 1-day gap, then a ~29-day gap.
        Schedule twoDays = Schedule.cron("0 0 1,2 * *", "UTC");
        assertThat(twoDays.minInterval(NOW)).contains(Duration.ofDays(1));
    }

    @Test
    void minIntervalSeesDaylightSavingShortDays() {
        // A daily 03:30 job in New York: the spring-forward day makes one gap 23 hours.
        Schedule daily = Schedule.cron("30 3 * * *", "America/New_York");
        assertThat(daily.minInterval(NOW)).contains(Duration.ofHours(23));
    }
}
