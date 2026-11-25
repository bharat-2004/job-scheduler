package com.jobscheduler.common.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps daily partitions of the runs table ahead of time and drops expired ones.
 * Also runs once per startup through the Flyway afterMigrate callback. Every role runs it,
 * because the API inserts manual runs too; the SQL function is idempotent and
 * guarded by an advisory lock.
 */
@Component
public class PartitionMaintenance {

    private static final Logger log = LoggerFactory.getLogger(PartitionMaintenance.class);

    private final JdbcTemplate jdbc;
    private final RetentionProperties retention;

    public PartitionMaintenance(JdbcTemplate jdbc, RetentionProperties retention) {
        this.jdbc = jdbc;
        this.retention = retention;
    }

    @Scheduled(cron = "0 17 * * * *", zone = "UTC")
    public void maintain() {
        try {
            jdbc.queryForObject("SELECT maintain_run_partitions(?, ?)::text", String.class,
                    retention.days(), retention.partitionsAheadDays());
            log.debug("run partitions maintained");
        } catch (RuntimeException e) {
            log.error("partition maintenance failed", e);
        }
    }
}
