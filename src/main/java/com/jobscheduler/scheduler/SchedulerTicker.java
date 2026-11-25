package com.jobscheduler.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The scheduler role's heartbeat. Every scheduler instance runs it; SKIP LOCKED splits the work,
 * so there is no leader election and no standby.
 */
@Component
@Profile("scheduler")
@ConditionalOnProperty(name = "app.scheduler.ticker-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerTicker {

    private static final Logger log = LoggerFactory.getLogger(SchedulerTicker.class);
    private static final Duration LOOK_AHEAD = Duration.ofMinutes(1);

    private final RunCreationService creation;
    private final RunDispatcher dispatcher;
    private final StuckRunSweeper sweeper;
    private final Clock clock;

    public SchedulerTicker(RunCreationService creation, RunDispatcher dispatcher, StuckRunSweeper sweeper, Clock clock) {
        this.creation = creation;
        this.dispatcher = dispatcher;
        this.sweeper = sweeper;
        this.clock = clock;
    }

    /**
     * Second 0 of every minute: create the NEXT minute's runs, publish them, sweep.
     *
     * <p>Design change from the LLD (which processed the current minute): cron occurrences fall on
     * second 0, exactly when the tick fires, so a current-minute window makes every cron run late by
     * the tick's own processing time. Looking one minute ahead, the 10:02 tick creates the 10:03 runs
     * and publishes them with a ~60 s delivery delay; RabbitMQ releases each one on time.
     */
    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    public void onMinute() {
        // +500ms guards against the trigger firing a hair early and picking the previous minute.
        Instant tickMinute = clock.instant().plusMillis(500).truncatedTo(ChronoUnit.MINUTES);
        Instant horizon = tickMinute.plus(LOOK_AHEAD).plus(Duration.ofMinutes(1));
        runStep("create", () -> creation.createDueRuns(horizon));
        runStep("dispatch", dispatcher::dispatchPending);
        runStep("sweep", sweeper::sweep);
    }

    /** Publishes leftovers: manual runs whose API fast path failed, batches that failed a minute ago. */
    @Scheduled(fixedDelayString = "${app.scheduler.dispatch-interval:10s}", initialDelayString = "5s")
    public void onTenSeconds() {
        runStep("dispatch", dispatcher::dispatchPending);
    }

    private void runStep(String name, Runnable step) {
        try {
            step.run();
        } catch (RuntimeException e) {
            log.error("scheduler step '{}' failed", name, e);
        }
    }
}
