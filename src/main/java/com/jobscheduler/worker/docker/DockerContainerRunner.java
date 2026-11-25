package com.jobscheduler.worker.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.command.PullImageResultCallback;
import com.github.dockerjava.api.command.WaitContainerResultCallback;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Volume;
import com.jobscheduler.worker.ContainerRunner;
import com.jobscheduler.worker.WorkerProperties;
import com.jobscheduler.worker.runtime.LanguageRuntime;
import com.jobscheduler.worker.runtime.LanguageRuntimes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One fresh, sandboxed container per run:
 * <ul>
 *   <li>read-only root filesystem; writable /tmp is a size-capped tmpfs</li>
 *   <li>non-root user, all capabilities dropped, no-new-privileges, process limit</li>
 *   <li>memory and CPU caps; its own bridge network with no route to the app's services</li>
 * </ul>
 * The script goes in with a docker copy into a per-run anonymous volume at /job (copying into a
 * read-only root filesystem is refused, and bind mounts would need a path on the Docker host,
 * which the worker container does not share). The volume is removed with the container.
 */
@Component
@Profile("worker")
public class DockerContainerRunner implements ContainerRunner {

    private static final Logger log = LoggerFactory.getLogger(DockerContainerRunner.class);

    static final String LABEL_RUN = "jobscheduler.run-id";
    static final String LABEL_HOST = "jobscheduler.worker-host";
    private static final String JOB_DIR = "/job";
    private static final int SIGKILL_EXIT = 137;
    private static final Duration BACKSTOP_MARGIN = Duration.ofSeconds(5);
    private static final Duration WAIT_MARGIN = Duration.ofSeconds(30);

    /** Kills containers at their deadline, independently of the threads running them. */
    private final ScheduledExecutorService watchdogs = Executors.newScheduledThreadPool(2, r -> {
        Thread t = new Thread(r, "run-watchdog");
        t.setDaemon(true);
        return t;
    });

    private final DockerClient docker;
    private final WorkerProperties props;
    private final LanguageRuntimes runtimes;

    public DockerContainerRunner(DockerClient docker, WorkerProperties props, LanguageRuntimes runtimes) {
        this.docker = docker;
        this.props = props;
        this.runtimes = runtimes;
    }

    /** Startup housekeeping: images present, sandbox network present, no orphans from a previous crash. */
    @EventListener(ApplicationReadyEvent.class)
    public void prepare() {
        try {
            removeOrphans();
            ensureNetwork();
            for (LanguageRuntime runtime : runtimes.all()) {
                ensureImage(runtime.image());
            }
        } catch (RuntimeException e) {
            log.error("docker preparation failed; runs will fail with INFRASTRUCTURE_ERROR until Docker is reachable", e);
        }
    }

    @Override
    public Result run(Request request) {
        WorkerProperties.Sandbox sandbox = props.sandbox();
        LanguageRuntime runtime = request.runtime();
        long memoryBytes = sandbox.memory().toBytes();

        HostConfig hostConfig = HostConfig.newHostConfig()
                .withMemory(memoryBytes)
                .withMemorySwap(memoryBytes)             // no swap on top of the memory cap
                .withNanoCPUs((long) (sandbox.cpus() * 1_000_000_000L))
                .withPidsLimit(sandbox.pidsLimit())
                .withReadonlyRootfs(true)
                .withTmpFs(Map.of("/tmp", "rw,nosuid,nodev,size=" + sandbox.tmpfsSize()))
                .withCapDrop(Capability.ALL)
                .withSecurityOpts(List.of("no-new-privileges"))
                .withPrivileged(false)
                .withNetworkMode(sandbox.network());

        long timeoutMillis = request.timeout().toMillis();
        CreateContainerResponse created = docker.createContainerCmd(runtime.image())
                .withName("run-" + request.runId())
                .withCmd(withInContainerDeadline(request, runtime))
                .withUser(sandbox.user())
                .withWorkingDir("/tmp")
                .withEnv("HOME=/tmp", "PYTHONDONTWRITEBYTECODE=1")
                .withVolumes(new Volume(JOB_DIR))
                .withLabels(Map.of(LABEL_RUN, request.runId().toString(), LABEL_HOST, props.hostname()))
                .withHostConfig(hostConfig)
                .exec();
        String containerId = created.getId();

        AtomicBoolean killedAtTimeout = new AtomicBoolean(false);
        ScheduledFuture<?> watchdog = null;
        try {
            docker.copyArchiveToContainerCmd(containerId)
                    .withHostResource(request.scriptFile().toString())
                    .withRemotePath(JOB_DIR)
                    .exec();

            // The timeout is enforced by a watchdog armed before start, not by where this thread happens
            // to be: a slow Docker API call (start, logs, wait) can never stretch a run past its timeout.
            long startRequested = System.nanoTime();
            watchdog = watchdogs.schedule(() -> {
                killedAtTimeout.set(true);
                log.info("run {} reached its {}s timeout; killing container", request.runId(), request.timeout().toSeconds());
                killQuietly(containerId);
            }, timeoutMillis, TimeUnit.MILLISECONDS);

            docker.startContainerCmd(containerId).exec();
            long startMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startRequested);
            if (startMillis > 2000) {
                log.warn("docker start took {} ms for run {}", startMillis, request.runId());
            }

            try (CappedLogCollector logs = docker.logContainerCmd(containerId)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withFollowStream(true)
                    .exec(new CappedLogCollector(request.logFile(), props.logSizeCap().toBytes()))) {

                // The watchdog (or the in-container deadline) ends the container; this wait only needs a
                // generous upper bound in case even the kill cannot get through.
                Integer exitCode = docker.waitContainerCmd(containerId)
                        .exec(new WaitContainerResultCallback())
                        .awaitStatusCode(timeoutMillis + WAIT_MARGIN.toMillis(), TimeUnit.MILLISECONDS);
                watchdog.cancel(false);
                logs.awaitCompletion(10, TimeUnit.SECONDS);

                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startRequested);
                boolean timedOut = killedAtTimeout.get()
                        || (exitCode != null && exitCode == SIGKILL_EXIT && elapsedMillis >= timeoutMillis);
                boolean oomKilled = !timedOut && wasOomKilled(containerId);
                return new Result(timedOut ? null : exitCode, timedOut, oomKilled, request.logFile(), logs.isTruncated());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while running " + request.runId(), e);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        } finally {
            if (watchdog != null) {
                watchdog.cancel(false);
            }
            removeQuietly(containerId);
        }
    }

    /**
     * Second, independent deadline inside the container: coreutils {@code timeout} SIGKILLs the script a
     * few seconds after the worker's watchdog should have, so even an unresponsive Docker API or a dead
     * worker cannot leave a script running forever. Every runtime image must ship coreutils.
     */
    private static List<String> withInContainerDeadline(Request request, LanguageRuntime runtime) {
        long backstopSeconds = request.timeout().plus(BACKSTOP_MARGIN).toSeconds();
        List<String> command = new ArrayList<>(List.of("timeout", "-s", "KILL", backstopSeconds + "s"));
        command.addAll(runtime.command(JOB_DIR + "/" + runtime.scriptFileName()));
        return command;
    }

    @PreDestroy
    void shutdownWatchdogs() {
        watchdogs.shutdownNow();
    }

    private boolean wasOomKilled(String containerId) {
        try {
            InspectContainerResponse.ContainerState state = docker.inspectContainerCmd(containerId).exec().getState();
            return Boolean.TRUE.equals(state.getOOMKilled());
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void killQuietly(String containerId) {
        try {
            docker.killContainerCmd(containerId).exec();
        } catch (RuntimeException e) {
            log.debug("kill of {} failed (probably already exited): {}", containerId, e.toString());
        }
    }

    private void removeQuietly(String containerId) {
        try {
            docker.removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true).exec();
        } catch (NotFoundException ignored) {
            // already gone
        } catch (RuntimeException e) {
            log.warn("could not remove container {}: {}", containerId, e.toString());
        }
    }

    private void removeOrphans() {
        List<Container> leftovers = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(LABEL_HOST, props.hostname()))
                .exec();
        for (Container c : leftovers) {
            log.warn("removing container {} left by a previous worker process (run {})",
                    c.getId(), c.getLabels().get(LABEL_RUN));
            removeQuietly(c.getId());
        }
    }

    private void ensureNetwork() {
        String name = props.sandbox().network();
        boolean exists = docker.listNetworksCmd().withNameFilter(name).exec().stream()
                .anyMatch(n -> name.equals(n.getName()));
        if (!exists) {
            docker.createNetworkCmd().withName(name).withDriver("bridge").exec();
            log.info("created sandbox network {}", name);
        }
    }

    private void ensureImage(String image) {
        try {
            docker.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            log.info("pulling {} ...", image);
            try {
                docker.pullImageCmd(image).exec(new PullImageResultCallback()).awaitCompletion(15, TimeUnit.MINUTES);
                log.info("pulled {}", image);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
