package com.jobscheduler.common.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateMachineTest {

    private static final Instant T0 = Instant.parse("2026-11-21T10:05:00Z");

    private static Run queued() {
        Job job = Job.create(UUID.randomUUID(), "j", Language.PYTHON, Schedule.cron("*/5 * * * *", "UTC"),
                Duration.ofSeconds(60), T0.minusSeconds(600));
        return job.createScheduledRun(T0, T0.minusSeconds(60));
    }

    private static Run running() {
        Run run = queued();
        run.markRunning("worker-1#abc", T0.plusSeconds(2));
        return run;
    }

    @Test
    void happyPath() {
        Run run = queued();
        run.markDispatched(T0.minusSeconds(59));
        assertThat(run.getStatus()).isEqualTo(RunStatus.QUEUED);

        run.markRunning("worker-1#abc", T0.plusSeconds(2));
        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(run.startDelay()).isEqualTo(Duration.ofSeconds(2));

        run.markSucceeded(0, "logs/x.log", null, T0.plusSeconds(10));
        assertThat(run.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(run.isTerminal()).isTrue();
        assertThat(run.getExitCode()).isZero();
    }

    @Test
    void cannotDispatchTwice() {
        Run run = queued();
        run.markDispatched(T0);
        assertThatThrownBy(() -> run.markDispatched(T0)).isInstanceOf(IllegalRunTransitionException.class);
    }

    @Test
    void cannotStartTwice() {
        Run run = running();
        // A duplicate message must never start the run again.
        assertThatThrownBy(() -> run.markRunning("worker-2#def", T0.plusSeconds(3)))
                .isInstanceOf(IllegalRunTransitionException.class);
    }

    @Test
    void finalStatesAreFinal() {
        Run succeeded = running();
        succeeded.markSucceeded(0, null, null, T0.plusSeconds(5));
        assertThatThrownBy(() -> succeeded.markFailed(FailureReason.WORKER_CRASHED, "late sweeper", T0))
                .isInstanceOf(IllegalRunTransitionException.class);
        assertThatThrownBy(() -> succeeded.markTimedOut(null, null, T0))
                .isInstanceOf(IllegalRunTransitionException.class);
    }

    @Test
    void cannotSucceedWithoutRunning() {
        assertThatThrownBy(() -> queued().markSucceeded(0, null, null, T0))
                .isInstanceOf(IllegalRunTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = {"SYSTEM_DOWN", "PREVIOUS_RUN_IN_PROGRESS", "JOB_DISABLED", "EXPIRED_IN_QUEUE"})
    void neverStartedReasonsOnlyFromQueued(FailureReason reason) {
        Run run = queued();
        run.markFailed(reason, "x", T0);
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(run.getFailureReason()).isEqualTo(reason);

        assertThatThrownBy(() -> running().markFailed(reason, "x", T0)).isInstanceOf(IllegalRunTransitionException.class);
    }

    @ParameterizedTest
    @EnumSource(value = FailureReason.class, names = {"SCRIPT_ERROR", "WORKER_CRASHED", "INFRASTRUCTURE_ERROR"})
    void startedReasonsOnlyFromRunning(FailureReason reason) {
        Run run = running();
        run.markFailed(reason, "x", T0.plusSeconds(5));
        assertThat(run.getStatus()).isEqualTo(RunStatus.FAILED);

        assertThatThrownBy(() -> queued().markFailed(reason, "x", T0)).isInstanceOf(IllegalRunTransitionException.class);
    }

    @Test
    void scriptErrorRecordsExitCodeAndLog() {
        Run run = running();
        run.markScriptError(2, "logs/a.log", "script exited with code 2", T0.plusSeconds(5));
        assertThat(run.getFailureReason()).isEqualTo(FailureReason.SCRIPT_ERROR);
        assertThat(run.getExitCode()).isEqualTo(2);
        assertThat(run.getLogPath()).isEqualTo("logs/a.log");
    }

    @Test
    void longDetailIsTruncatedToColumnSize() {
        Run run = queued();
        run.markFailed(FailureReason.SYSTEM_DOWN, "x".repeat(2000), T0);
        assertThat(run.getFailureDetail()).hasSize(500);
    }

    @Test
    void statusTransitionTable() {
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.RUNNING)).isTrue();
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.QUEUED.canTransitionTo(RunStatus.SUCCESS)).isFalse();
        assertThat(RunStatus.RUNNING.canTransitionTo(RunStatus.QUEUED)).isFalse();
        for (RunStatus terminal : new RunStatus[]{RunStatus.SUCCESS, RunStatus.FAILED, RunStatus.TIMED_OUT}) {
            for (RunStatus target : RunStatus.values()) {
                assertThat(terminal.canTransitionTo(target)).isFalse();
            }
        }
    }
}
