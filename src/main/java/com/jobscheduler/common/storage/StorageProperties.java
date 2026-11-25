package com.jobscheduler.common.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Any S3-compatible object store. Credentials come from environment variables only. */
@ConfigurationProperties("app.storage")
public record StorageProperties(
        String endpoint,
        String accessKey,
        String secretKey,
        @DefaultValue("jobscheduler") String bucket,
        @DefaultValue("us-east-1") String region) {
}
