package com.jobscheduler.scheduler;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param batchSize     jobs/runs locked per short transaction
 * @param lateGrace     a run whose time passed less than this long ago is still created and runs at once
 *                      (the start delay alert covers it); older counts as missed (SYSTEM_DOWN). Covers a
 *                      job skipped for a tick because another transaction held its row lock at that moment.
 * @param stuckRunGrace extra time after a run's timeout before the sweeper declares its worker dead
 * @param queuedExpiry  a run still QUEUED this long after its scheduled time is failed (EXPIRED_IN_QUEUE)
 */
@ConfigurationProperties("app.scheduler")
public record SchedulerProperties(
        @DefaultValue("500") int batchSize,
        @DefaultValue("3m") Duration lateGrace,
        @DefaultValue("60s") Duration stuckRunGrace,
        @DefaultValue("30m") Duration queuedExpiry) {
}
