package com.jobscheduler.worker;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.time.Duration;

/**
 * @param hostname            stable name of this worker host; containers it starts are labelled with it,
 *                            so a restarted worker can remove containers its previous process left behind
 * @param concurrency         listener threads = run slots; prefetch is 1, so never more messages than slots
 * @param delayAlertThreshold a run starting later than this triggers an email
 * @param logSizeCap          log bytes kept per run; the rest is dropped with a marker line
 * @param workDir             where scripts and logs live while a run executes
 */
@ConfigurationProperties("app.worker")
public record WorkerProperties(
        @DefaultValue("local") String hostname,
        @DefaultValue("5") int concurrency,
        @DefaultValue("5m") Duration delayAlertThreshold,
        @DefaultValue("10MB") DataSize logSizeCap,
        @DefaultValue("/tmp/jobscheduler") String workDir,
        @DefaultValue Docker docker,
        @DefaultValue Sandbox sandbox) {

    /**
     * @param apiVersion Docker Engine 29 rejects API versions below 1.44; pinning one avoids
     *                   docker-java's old default.
     */
    public record Docker(
            @DefaultValue("unix:///var/run/docker.sock") String host,
            @DefaultValue("1.44") String apiVersion) {
    }

    /** Per-container limits (HLD "Sandbox rules per container"). */
    public record Sandbox(
            @DefaultValue("256MB") DataSize memory,
            @DefaultValue("0.5") double cpus,
            @DefaultValue("64") long pidsLimit,
            @DefaultValue("64m") String tmpfsSize,
            @DefaultValue("65534:65534") String user,
            @DefaultValue("jobscheduler-sandbox") String network,
            @DefaultValue("python:3.12-slim") String pythonImage) {
    }
}
