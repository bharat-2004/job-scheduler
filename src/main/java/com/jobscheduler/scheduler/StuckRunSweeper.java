package com.jobscheduler.scheduler;

import com.jobscheduler.common.domain.FailureReason;
import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.repository.RunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;

/**
 * Step 3: closes runs nobody will ever finish.
 * <ul>
 *   <li>RUNNING past timeout + grace: the worker died mid-run. FAILED (WORKER_CRASHED), never rerun.</li>
 *   <li>QUEUED long past its time (added in implementation): its message was lost, or it waited
 *       through the queue's delivery limit. Without this, a lost manual run would block manual
 *       triggers of its job forever. FAILED (EXPIRED_IN_QUEUE).</li>
 * </ul>
 */
@Service
@Profile("scheduler")
public class StuckRunSweeper {

    private static final Logger log = LoggerFactory.getLogger(StuckRunSweeper.class);

    private final RunRepository runs;
    private final TransactionTemplate tx;
    private final SchedulerProperties props;
    private final Clock clock;

    public StuckRunSweeper(RunRepository runs, TransactionTemplate tx, SchedulerProperties props, Clock clock) {
        this.runs = runs;
        this.tx = tx;
        this.props = props;
        this.clock = clock;
    }

    public void sweep() {
        int crashed = sweepBatches(now -> runs.lockStuckRunning(now, props.stuckRunGrace().toSeconds(), props.batchSize()),
                FailureReason.WORKER_CRASHED, "no result from the worker within timeout + "
                        + props.stuckRunGrace().toSeconds() + "s");
        int expired = sweepBatches(now -> runs.lockExpiredQueued(now.minus(props.queuedExpiry()), props.batchSize()),
                FailureReason.EXPIRED_IN_QUEUE, "not started within " + props.queuedExpiry().toMinutes()
                        + " minutes of its scheduled time");
        if (crashed + expired > 0) {
            log.warn("sweeper failed {} stuck RUNNING runs and {} expired QUEUED runs", crashed, expired);
        }
    }

    private int sweepBatches(Function<Instant, List<Run>> finder, FailureReason reason, String detail) {
        int total = 0;
        while (true) {
            int swept;
            try {
                swept = tx.execute(status -> {
                    Instant now = clock.instant();
                    List<Run> stuck = finder.apply(now);
                    stuck.forEach(run -> run.markFailed(reason, detail, now));
                    return stuck.size();
                });
            } catch (RuntimeException e) {
                log.error("sweep for {} failed", reason, e);
                return total;
            }
            total += swept;
            if (swept < props.batchSize()) {
                return total;
            }
        }
    }
}
