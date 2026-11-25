package com.jobscheduler.common.storage;

import com.jobscheduler.common.config.RetentionProperties;
import com.jobscheduler.common.domain.Language;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.SetBucketLifecycleArgs;
import io.minio.UploadObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.Expiration;
import io.minio.messages.LifecycleConfiguration;
import io.minio.messages.LifecycleRule;
import io.minio.messages.RuleFilter;
import io.minio.messages.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Scripts and logs in one S3 bucket, through the MinIO Java SDK (which speaks plain S3, so the
 * server can be MinIO, RustFS, AWS S3, ...). Logs expire through a bucket lifecycle rule.
 */
@Component
public class S3ObjectStorage implements ScriptStorage, LogStorage {

    private static final Logger log = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final MinioClient client;
    private final StorageProperties props;
    private final RetentionProperties retention;

    public S3ObjectStorage(StorageProperties props, RetentionProperties retention) {
        this.props = props;
        this.retention = retention;
        this.client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .region(props.region())
                .build();
    }

    /** Creates the bucket and the log-expiry rule. Best effort: the app still starts if storage is down. */
    @EventListener(ApplicationReadyEvent.class)
    public void ensureBucket() {
        try {
            if (!client.bucketExists(BucketExistsArgs.builder().bucket(props.bucket()).build())) {
                client.makeBucket(MakeBucketArgs.builder().bucket(props.bucket()).build());
                log.info("created bucket {}", props.bucket());
            }
        } catch (Exception e) {
            log.warn("could not verify bucket {} at {}: {}", props.bucket(), props.endpoint(), e.toString());
            return;
        }
        try {
            LifecycleRule expireLogs = new LifecycleRule(Status.ENABLED, null,
                    new Expiration((java.time.ZonedDateTime) null, retention.days(), null),
                    new RuleFilter("logs/"), "expire-logs", null, null, null);
            client.setBucketLifecycle(SetBucketLifecycleArgs.builder()
                    .bucket(props.bucket())
                    .config(new LifecycleConfiguration(List.of(expireLogs)))
                    .build());
        } catch (Exception e) {
            log.warn("could not set log lifecycle rule on {}: {}", props.bucket(), e.toString());
        }
    }

    // ---- ScriptStorage ----

    @Override
    public void upload(UUID jobId, int version, Language language, byte[] content) {
        String path = ScriptStorage.path(jobId, version, language);
        try {
            client.putObject(PutObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(path)
                    .stream(new ByteArrayInputStream(content), content.length, -1)
                    .contentType("text/plain; charset=utf-8")
                    .build());
        } catch (Exception e) {
            throw new StorageException("failed to upload " + path, e);
        }
    }

    @Override
    public byte[] fetch(UUID jobId, int version, Language language) {
        String path = ScriptStorage.path(jobId, version, language);
        try (InputStream in = client.getObject(GetObjectArgs.builder().bucket(props.bucket()).object(path).build())) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new StorageException("failed to fetch " + path, e);
        }
    }

    // ---- LogStorage ----

    @Override
    public String upload(UUID jobId, UUID runId, Path logFile) {
        String path = LogStorage.path(jobId, runId);
        try {
            client.uploadObject(UploadObjectArgs.builder()
                    .bucket(props.bucket())
                    .object(path)
                    .filename(logFile.toString())
                    .contentType("text/plain; charset=utf-8")
                    .build());
            return path;
        } catch (Exception e) {
            throw new StorageException("failed to upload " + path, e);
        }
    }

    @Override
    public Optional<InputStream> open(String logPath) {
        try {
            return Optional.of(client.getObject(GetObjectArgs.builder().bucket(props.bucket()).object(logPath).build()));
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return Optional.empty();
            }
            throw new StorageException("failed to read " + logPath, e);
        } catch (Exception e) {
            throw new StorageException("failed to read " + logPath, e);
        }
    }
}
