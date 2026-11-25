package com.jobscheduler.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Run records and logs are kept this many days; partitions are created this many days ahead. */
@ConfigurationProperties("app.retention")
public record RetentionProperties(
        @DefaultValue("30") int days,
        @DefaultValue("7") int partitionsAheadDays) {
}
