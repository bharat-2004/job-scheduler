package com.jobscheduler.integration;

import com.jobscheduler.api.RunService;
import com.jobscheduler.api.dto.ApiDtos.RunResponse;
import com.jobscheduler.api.web.ApiExceptions.JobAlreadyRunningException;
import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.RunId;
import com.jobscheduler.common.domain.RunStatus;
import com.jobscheduler.common.messaging.RunMessage;
import com.jobscheduler.common.repository.RunRepository;
import com.jobscheduler.worker.RunExecutionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class ApiAndWorkerIT extends IntegrationTestBase {

    @Autowired RunService runService;
    @Autowired RunExecutionService executionService;
    @Autowired RunRepository runs;

    @Test
    void concurrentManualTriggersCreateExactlyOneRun() throws Exception {
        UUID owner = newUser();
        Job job = newEveryMinuteJob(owner, "manual");

        List<Future<RunResponse>> results = runConcurrently(10, () -> runService.triggerManual(owner, job.getId()));

        int created = 0;
        int conflicts = 0;
        for (Future<RunResponse> f : results) {
            try {
                f.get();
                created++;
            } catch (ExecutionException e) {
                assertThat(e.getCause()).isInstanceOf(JobAlreadyRunningException.class);
                conflicts++;
            }
        }
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM runs", Long.class)).isEqualTo(1);
        // Fast path: published straight away and marked dispatched.
        assertThat(publisher.published).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM runs WHERE dispatched_at IS NOT NULL", Long.class)).isEqualTo(1);
    }

    @Test
    void manualRunStaysUndispatchedWhenTheFastPathFails() {
        UUID owner = newUser();
        Job job = newEveryMinuteJob(owner, "fast-path");
        publisher.failing.set(true);

        RunResponse run = runService.triggerManual(owner, job.getId());

        assertThat(run.status()).isEqualTo(RunStatus.QUEUED);
        assertThat(run.dispatchedAt()).as("left for the 10-second dispatcher").isNull();
    }

    @Test
    void duplicateMessagesNeverStartARunTwice() throws Exception {
        UUID owner = newUser();
        Job job = newEveryMinuteJob(owner, "dup-msg");
        RunResponse run = runService.triggerManual(owner, job.getId());
        RunMessage message = new RunMessage(run.id(), job.getId());

        // The same message delivered to three workers at once (outbox republish + redelivery).
        runConcurrently(3, () -> {
            executionService.execute(message);
            return null;
        });

        assertThat(containerRunner.invocations.get()).isEqualTo(1);
        var finished = runs.findById(new RunId(run.id(), run.scheduledTime())).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(RunStatus.SUCCESS);
        assertThat(finished.getLogPath()).isNotNull();
        assertThat(finished.getWorkerId()).isNotBlank();
    }

    @Test
    void scheduledRunWhileAnotherRunIsRunningFailsWithPreviousRunInProgress() {
        UUID owner = newUser();
        Job job = newEveryMinuteJob(owner, "overlap");
        tx.executeWithoutResult(s -> {
            var active = job.createManualRun(T.minusSeconds(5));
            active.markRunning("other-worker#1", T.minusSeconds(4));
            jobs.insert(active);
        });
        var scheduled = job.createScheduledRun(T, T);
        tx.executeWithoutResult(s -> jobs.insert(scheduled));

        executionService.execute(new RunMessage(scheduled.getId(), job.getId()));

        var result = runs.findById(new RunId(scheduled.getId(), scheduled.getScheduledTime())).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(result.getFailureReason().name()).isEqualTo("PREVIOUS_RUN_IN_PROGRESS");
        assertThat(containerRunner.invocations.get()).isZero();
    }

    @Test
    void nonZeroExitIsAScriptError() {
        UUID owner = newUser();
        Job job = newEveryMinuteJob(owner, "fails");
        containerRunner.exitCode = 3;
        RunResponse run = runService.triggerManual(owner, job.getId());

        executionService.execute(new RunMessage(run.id(), job.getId()));

        var result = runs.findById(new RunId(run.id(), run.scheduledTime())).orElseThrow();
        assertThat(result.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(result.getFailureReason().name()).isEqualTo("SCRIPT_ERROR");
        assertThat(result.getExitCode()).isEqualTo(3);
    }
}
