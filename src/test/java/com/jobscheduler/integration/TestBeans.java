package com.jobscheduler.integration;

import com.jobscheduler.common.domain.Language;
import com.jobscheduler.common.messaging.RunPublisher;
import com.jobscheduler.common.storage.LogStorage;
import com.jobscheduler.common.storage.ScriptStorage;
import com.jobscheduler.worker.ContainerRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Stand-ins for everything outside PostgreSQL: clock, RabbitMQ, object storage, Docker. */
@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    RecordingPublisher recordingPublisher() {
        return new RecordingPublisher();
    }

    @Bean
    @Primary
    FakeContainerRunner fakeContainerRunner() {
        return new FakeContainerRunner();
    }

    @Bean
    @Primary
    InMemoryStorage inMemoryStorage() {
        return new InMemoryStorage();
    }

    public static class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.EPOCH);

        public void set(Instant instant) {
            now.set(instant);
        }

        @Override
        public Instant instant() {
            return now.get();
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }

    public static class RecordingPublisher implements RunPublisher {
        public final List<Delivery> published = new CopyOnWriteArrayList<>();
        public final AtomicBoolean failing = new AtomicBoolean(false);

        public void reset() {
            published.clear();
            failing.set(false);
        }

        @Override
        public void publishAll(List<Delivery> deliveries) {
            if (failing.get()) {
                throw new IllegalStateException("broker unavailable (simulated)");
            }
            published.addAll(deliveries);
        }
    }

    /** Pretends to run the script: counts invocations, sleeps a little so overlapping calls really overlap. */
    public static class FakeContainerRunner implements ContainerRunner {
        public final AtomicInteger invocations = new AtomicInteger();
        public volatile int exitCode = 0;

        public void reset() {
            invocations.set(0);
            exitCode = 0;
        }

        @Override
        public Result run(Request request) {
            invocations.incrementAndGet();
            try {
                Files.writeString(request.logFile(), "fake output for " + request.runId() + "\n");
                Thread.sleep(200);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return new Result(exitCode, false, false, request.logFile(), false);
        }
    }

    public static class InMemoryStorage implements ScriptStorage, LogStorage {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void upload(UUID jobId, int version, Language language, byte[] content) {
            objects.put(ScriptStorage.path(jobId, version, language), content);
        }

        @Override
        public byte[] fetch(UUID jobId, int version, Language language) {
            return objects.getOrDefault(ScriptStorage.path(jobId, version, language), "print('hi')".getBytes());
        }

        @Override
        public String upload(UUID jobId, UUID runId, Path logFile) {
            String path = LogStorage.path(jobId, runId);
            try {
                objects.put(path, Files.readAllBytes(logFile));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return path;
        }

        @Override
        public Optional<InputStream> open(String logPath) {
            return Optional.ofNullable(objects.get(logPath)).map(ByteArrayInputStream::new);
        }
    }
}
