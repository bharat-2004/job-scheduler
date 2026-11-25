package com.jobscheduler.integration;

import com.jobscheduler.common.domain.FailureReason;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.scheduler.RunCreationService;
import com.jobscheduler.scheduler.RunDispatcher;
import com.jobscheduler.scheduler.StuckRunSweeper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchedulerIT extends IntegrationTestBase {

    private static final Instant WINDOW_END = T.plusSeconds(60);

    @Autowired RunCreationService creation;
    @Autowired RunDispatcher dispatcher;
    @Autowired StuckRunSweeper sweeper;

    @Test
    void concurrentSchedulersCreateEachRunExactlyOnce() throws Exception {
        UUID owner = newUser();
        int jobCount = 100;
        for (int i = 0; i < jobCount; i++) {
            newEveryMinuteJob(owner, "job-" + i);
        }

        // Four "scheduler instances" process the same window at the same moment (batch size 7).
        List<Future<Integer>> results = runConcurrently(4, () -> creation.createDueRuns(WINDOW_END));
        int processed = 0;
        for (Future<Integer> f : results) {
            processed += f.get();
        }

        assertThat(processed).as("every due job handled by exactly one instance").isEqualTo(jobCount);
        assertThat(count("SELECT count(*) FROM runs")).isEqualTo(jobCount);
        assertThat(count("SELECT count(DISTINCT job_id) FROM runs WHERE scheduled_time = ?", T)).isEqualTo(jobCount);
        assertThat(count("SELECT count(*) FROM jobs WHERE next_run_time = ?", WINDOW_END))
                .as("every job advanced to the next minute").isEqualTo(jobCount);

        // A second tick for the same window finds nothing to do.
        assertThat(creation.createDueRuns(WINDOW_END)).isZero();
        assertThat(count("SELECT count(*) FROM runs")).isEqualTo(jobCount);
    }

    @Test
    void partialUniqueIndexRejectsDuplicateScheduledRunsButNotManualOnes() {
        Job job = newEveryMinuteJob(newUser(), "dup");
        tx.executeWithoutResult(s -> jobs.insert(job.createScheduledRun(T, T)) );

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> jobs.insert(job.createScheduledRun(T, T))))
                .isInstanceOf(DataIntegrityViolationException.class)
                // The violation names the partition's copy of ux_runs_scheduled_once (e.g. runs_20261121_..._idx1).
                .hasMessageContaining("Key (job_id, scheduled_time)");

        tx.executeWithoutResult(s -> {
            jobs.insert(job.createManualRun(T));
            jobs.insert(job.createManualRun(T));
        });
        assertThat(count("SELECT count(*) FROM runs WHERE trigger_type = 'MANUAL'")).isEqualTo(2);
    }

    @Test
    void existingScheduledRunIsNotCreatedAgainButTheScheduleStillAdvances() {
        UUID owner = newUser();
        Job withRun = newEveryMinuteJob(owner, "has-run");       // due at T, run already there
        Job withoutRun = newEveryMinuteJob(owner, "no-run");     // due at T, same batch
        tx.executeWithoutResult(s -> jobs.insert(withRun.createScheduledRun(T, T)));
        clock.set(T.minusSeconds(60).plusMillis(50));

        assertThat(creation.createDueRuns(WINDOW_END)).isEqualTo(2);

        assertThat(count("SELECT count(*) FROM runs WHERE job_id = ?", withRun.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM runs WHERE job_id = ?", withoutRun.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM jobs WHERE next_run_time = ?", WINDOW_END)).isEqualTo(2);
    }

    @Test
    void runMissedDuringDowntimeIsRecordedOnceAndScheduleJumpsAhead() {
        Job job = newEveryMinuteJob(newUser(), "missed");   // due at T
        Instant tick = T.plus(Duration.ofHours(2)).plusMillis(50);   // first tick after a 2-hour outage
        clock.set(tick);

        creation.createDueRuns(T.plus(Duration.ofHours(2)).plusSeconds(120));   // look-ahead horizon

        List<Map<String, Object>> runs = jdbc.queryForList(
                "SELECT status, failure_reason, scheduled_time FROM runs WHERE job_id = ? ORDER BY scheduled_time", job.getId());
        assertThat(runs).as("one SYSTEM_DOWN record, no catch-up, then the next future run").hasSize(2);
        assertThat(runs.get(0)).containsEntry("status", "FAILED").containsEntry("failure_reason", "SYSTEM_DOWN");
        assertThat(runs.get(1)).containsEntry("status", "QUEUED")
                .containsEntry("scheduled_time", java.sql.Timestamp.from(T.plus(Duration.ofHours(2)).plusSeconds(60)));
        assertThat(count("SELECT count(*) FROM jobs WHERE next_run_time = ?",
                T.plus(Duration.ofHours(2)).plusSeconds(120))).isEqualTo(1);
    }

    @Test
    void slightlyLateRunIsStillCreatedNormally() {
        // Due at T, but its row was locked during the previous tick; this tick fires 50 ms after T.
        Job job = newEveryMinuteJob(newUser(), "late");
        clock.set(T.plusMillis(50));

        creation.createDueRuns(T.plusSeconds(120));

        assertThat(jdbc.queryForList("SELECT status FROM runs WHERE job_id = ? ORDER BY scheduled_time", String.class, job.getId()))
                .as("the late run (starts at once) and the next one (delayed), neither failed")
                .containsExactly("QUEUED", "QUEUED");
    }

    @Test
    void lookAheadCreatesNextMinutesRunsWithADeliveryDelay() {
        Job job = newEveryMinuteJob(newUser(), "ahead");    // first run at T
        clock.set(T.minusSeconds(60).plusMillis(50));        // the tick one minute before

        creation.createDueRuns(T.plusSeconds(60));
        dispatcher.dispatchPending();

        assertThat(publisher.published).singleElement()
                .satisfies(d -> assertThat(d.deliverAt()).as("released by RabbitMQ at T, not now").isEqualTo(T));
        assertThat(count("SELECT count(*) FROM jobs WHERE id = ? AND next_run_time = ?", job.getId(), T.plusSeconds(60)))
                .isEqualTo(1);
    }

    @Test
    void outboxPublishesEachRunOnceEvenWithConcurrentDispatchers() throws Exception {
        UUID owner = newUser();
        for (int i = 0; i < 30; i++) {
            newEveryMinuteJob(owner, "job-" + i);
        }
        creation.createDueRuns(WINDOW_END);

        // Broker down: nothing is lost, nothing is marked dispatched.
        publisher.failing.set(true);
        assertThat(dispatcher.dispatchPending()).isZero();
        assertThat(count("SELECT count(*) FROM runs WHERE dispatched_at IS NULL")).isEqualTo(30);

        // Broker back: two dispatchers race, every run is published exactly once.
        publisher.failing.set(false);
        runConcurrently(2, dispatcher::dispatchPending);
        assertThat(publisher.published).hasSize(30);
        assertThat(publisher.published.stream().map(d -> d.runId()).collect(Collectors.toSet())).hasSize(30);
        assertThat(count("SELECT count(*) FROM runs WHERE dispatched_at IS NULL")).isZero();
        assertThat(publisher.published).allMatch(d -> d.deliverAt().equals(T));
    }

    @Test
    void sweeperFailsStuckRunningAndExpiredQueuedRuns() {
        Job job = newEveryMinuteJob(newUser(), "stuck");
        tx.executeWithoutResult(s -> {
            var running = job.createScheduledRun(T, T);
            running.markRunning("dead-worker#1", T);
            jobs.insert(running);
            jobs.insert(job.createManualRun(T.plusSeconds(1)));
        });

        clock.set(T.plusSeconds(60));                 // timeout 30s + grace 60s not yet passed
        sweeper.sweep();
        assertThat(count("SELECT count(*) FROM runs WHERE status = 'FAILED'")).isZero();

        clock.set(T.plus(Duration.ofMinutes(31)));    // past both the grace and the 30-minute queue expiry
        sweeper.sweep();
        Map<String, String> reasons = jdbc.queryForList("SELECT trigger_type, failure_reason FROM runs").stream()
                .collect(Collectors.toMap(r -> (String) r.get("trigger_type"), r -> (String) r.get("failure_reason")));
        assertThat(reasons).containsEntry("SCHEDULED", FailureReason.WORKER_CRASHED.name())
                .containsEntry("MANUAL", FailureReason.EXPIRED_IN_QUEUE.name());
    }

    @Test
    void partitionsExistAheadOfTime() {
        String today = java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString().replace("-", "");
        String inAWeek = java.time.LocalDate.now(java.time.ZoneOffset.UTC).plusDays(7).toString().replace("-", "");
        List<String> partitions = jdbc.queryForList("""
                SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
                JOIN pg_class p ON p.oid = i.inhparent WHERE p.relname = 'runs'""", String.class);
        assertThat(partitions).contains("runs_" + today, "runs_" + inAWeek).hasSize(38);
    }

    private long count(String sql, Object... args) {
        Object[] bound = java.util.Arrays.stream(args)
                .map(a -> a instanceof Instant i ? java.sql.Timestamp.from(i) : a)
                .toArray();
        Long value = jdbc.queryForObject(sql, Long.class, bound);
        return value == null ? 0 : value;
    }
}
