package com.jobscheduler.scheduler;

import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.messaging.RunPublisher;
import com.jobscheduler.common.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Step 2, the outbox relay: publishes every run with {@code dispatched_at IS NULL}.
 *
 * <p>Publish and markDispatched share one transaction. If publishing fails, the transaction rolls
 * back and the runs stay undispatched. If the process dies after the broker confirmed but before
 * commit, the runs are published again later; the worker ignores the duplicate because the run is
 * no longer QUEUED. At-least-once publishing + idempotent consumer = exactly-once execution start.
 */
@Service
@Profile("scheduler")
public class RunDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);

    private final RunRepository runs;
    private final RunPublisher publisher;
    private final TransactionTemplate tx;
    private final SchedulerProperties props;
    private final Clock clock;

    public RunDispatcher(RunRepository runs, RunPublisher publisher, TransactionTemplate tx,
                         SchedulerProperties props, Clock clock) {
        this.runs = runs;
        this.publisher = publisher;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public int dispatchPending() {
        int total = 0;
        while (true) {
            Integer published;
            try {
                published = tx.execute(status -> dispatchBatch());
            } catch (RuntimeException e) {
                log.warn("dispatch batch failed; runs stay undispatched and are retried: {}", e.toString());
                break;
            }
            if (published == null || published == 0) {
                break;
            }
            total += published;
            if (published < props.batchSize()) {
                break;
            }
        }
        if (total > 0) {
            log.info("dispatched {} runs", total);
        }
        return total;
    }

    private int dispatchBatch() {
        List<Run> pending = runs.lockUndispatched(props.batchSize());
        if (pending.isEmpty()) {
            return 0;
        }
        // Delivery time = scheduled time; the publisher sends anything already due without delay.
        publisher.publishAll(pending.stream()
                .map(r -> new RunPublisher.Delivery(r.getId(), r.getJobId(), r.getScheduledTime()))
                .toList());
        Instant now = clock.instant();
        pending.forEach(r -> r.markDispatched(now));
        return pending.size();
    }
}
