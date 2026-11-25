package com.jobscheduler.worker;

import com.jobscheduler.worker.runtime.LanguageRuntime;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

/**
 * Runs one script in a fresh sandbox and returns when it exits or is killed at its timeout.
 * Docker today; a Kubernetes Job implementation would slot in without touching the business logic.
 */
public interface ContainerRunner {

    Result run(Request request);

    /**
     * @param scriptFile script on the worker's disk
     * @param logFile    where combined stdout/stderr is written (capped)
     */
    record Request(UUID runId, LanguageRuntime runtime, Path scriptFile, Duration timeout, Path logFile) {
    }

    /**
     * @param exitCode    null when the container was killed at the timeout
     * @param oomKilled   the kernel killed it for exceeding the memory limit
     * @param logTruncated output went past the log size cap
     */
    record Result(Integer exitCode, boolean timedOut, boolean oomKilled, Path logFile, boolean logTruncated) {
    }
}
