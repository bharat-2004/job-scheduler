package com.jobscheduler.integration;

import com.jobscheduler.common.domain.Job;
import com.jobscheduler.common.domain.Language;
import com.jobscheduler.common.domain.Schedule;
import com.jobscheduler.common.domain.User;
import com.jobscheduler.common.repository.JobRepository;
import com.jobscheduler.common.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import java.util.List;

/**
 * Real PostgreSQL, all three roles in one context, outside systems stubbed (see {@link TestBeans}).
 * The ticker is off: tests call the scheduler steps directly with a controllable clock.
 *
 * <p>Database: set IT_DB_URL (plus IT_DB_USER / IT_DB_PASSWORD) to use an existing PostgreSQL, e.g. the
 * Compose one on port 5437; otherwise Testcontainers starts postgres:17-alpine.
 */
@SpringBootTest(properties = {
        "app.scheduler.ticker-enabled=false",
        "app.scheduler.batch-size=7",                 // small batches: exercise the batch loop and SKIP LOCKED
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "app.jwt.secret=integration-test-secret-integration-test-secret",
        "app.storage.endpoint=http://localhost:1",
        "app.storage.access-key=x",
        "app.storage.secret-key=y",
        "logging.level.com.jobscheduler=WARN"
})
@ActiveProfiles({"api", "scheduler", "worker"})
@Import(TestBeans.class)
public abstract class IntegrationTestBase {

    private static PostgreSQLContainer postgres;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        String url = System.getenv("IT_DB_URL");
        if (url != null && !url.isBlank()) {
            registry.add("spring.datasource.url", () -> url);
            registry.add("spring.datasource.username", () -> envOr("IT_DB_USER", "jobscheduler"));
            registry.add("spring.datasource.password", () -> envOr("IT_DB_PASSWORD", "jobscheduler-dev"));
        } else {
            if (postgres == null) {
                postgres = new PostgreSQLContainer("postgres:17-alpine");
                postgres.start();
            }
            registry.add("spring.datasource.url", postgres::getJdbcUrl);
            registry.add("spring.datasource.username", postgres::getUsername);
            registry.add("spring.datasource.password", postgres::getPassword);
        }
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "20");
    }

    private static String envOr(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected TransactionTemplate tx;
    @Autowired protected UserRepository users;
    @Autowired protected JobRepository jobs;
    @Autowired protected TestBeans.MutableClock clock;
    @Autowired protected TestBeans.RecordingPublisher publisher;
    @Autowired protected TestBeans.FakeContainerRunner containerRunner;

    /** A fixed "now" at the start of a minute, so windows are easy to reason about. */
    protected static final Instant T = Instant.parse("2026-11-21T10:02:00Z");

    @BeforeEach
    void resetState() {
        jdbc.execute("TRUNCATE runs, jobs, users");
        clock.set(T);
        publisher.reset();
        containerRunner.reset();
    }

    protected UUID newUser() {
        User user = User.register("user-" + UUID.randomUUID() + "@example.com", "{noop}x", clock.instant());
        tx.executeWithoutResult(s -> users.insert(user));
        return user.getId();
    }

    /** An every-minute cron job created two minutes before T, so its first run is exactly T. */
    protected Job newEveryMinuteJob(UUID ownerId, String name) {
        Instant created = T.minus(Duration.ofSeconds(90));
        Job job = Job.create(ownerId, name, Language.PYTHON, Schedule.cron("* * * * *", "UTC"),
                Duration.ofSeconds(30), created);
        tx.executeWithoutResult(s -> jobs.insert(job));
        return job;
    }

    /** Starts all tasks at the same instant (latch) on separate threads and returns their results. */
    protected static <R> List<Future<R>> runConcurrently(int threads, Callable<R> task) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<R>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return task.call();
            }));
        }
        start.countDown();
        pool.shutdown();
        if (!pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent tasks did not finish");
        }
        return futures;
    }
}
