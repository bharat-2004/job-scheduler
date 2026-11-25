package com.jobscheduler.common.repository;

import com.jobscheduler.common.domain.Run;
import com.jobscheduler.common.domain.RunId;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RunRepository extends JpaRepository<Run, RunId>, InsertSupport {

    /**
     * A run by id alone, only if its job belongs to {@code ownerId} and is not deleted (one query).
     * No partition pruning: one index probe per daily partition, unavoidable for GET /runs/{id}.
     */
    @Query("""
            SELECT r FROM Run r
             WHERE r.id = :id
               AND EXISTS (SELECT j.id FROM Job j
                            WHERE j.id = r.jobId AND j.ownerId = :ownerId AND j.deletedAt IS NULL)
            """)
    Optional<Run> findOwned(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    /** Lock by id alone (the queue message has no scheduled_time): probes every partition. */
    @Query(value = "SELECT * FROM runs WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Run> lockByRunId(@Param("id") UUID id);

    /** Lock by the full primary key: pruned to one partition. */
    @Query(value = "SELECT * FROM runs WHERE id = :id AND scheduled_time = :scheduledTime FOR UPDATE",
            nativeQuery = true)
    Optional<Run> lockByKey(@Param("id") UUID id, @Param("scheduledTime") Instant scheduledTime);

    Page<Run> findByJobIdOrderByScheduledTimeDesc(UUID jobId, Pageable pageable);

    Optional<Run> findFirstByJobIdOrderByScheduledTimeDesc(UUID jobId);

    /** What blocks a manual trigger (409): a RUNNING run, or a manual run still QUEUED. */
    @Query("""
            SELECT COUNT(r) > 0 FROM Run r
             WHERE r.jobId = :jobId
               AND (r.status = com.jobscheduler.common.domain.RunStatus.RUNNING
                    OR (r.status = com.jobscheduler.common.domain.RunStatus.QUEUED
                        AND r.triggerType = com.jobscheduler.common.domain.TriggerType.MANUAL))
            """)
    boolean blocksManualTrigger(@Param("jobId") UUID jobId);

    /**
     * SCHEDULED runs of these jobs at any of these times: one query per scheduler batch instead of one per
     * job (a query inside the loop would also force a flush per job and defeat JDBC insert batching).
     * The caller matches exact (job, time) pairs; normally this returns nothing.
     */
    @Query("""
            SELECT r FROM Run r
             WHERE r.triggerType = com.jobscheduler.common.domain.TriggerType.SCHEDULED
               AND r.jobId IN :jobIds
               AND r.scheduledTime IN :times
            """)
    List<Run> findScheduledRuns(@Param("jobIds") Collection<UUID> jobIds, @Param("times") Collection<Instant> times);

    @Query("SELECT COUNT(r) > 0 FROM Run r WHERE r.jobId = :jobId AND r.status = com.jobscheduler.common.domain.RunStatus.RUNNING AND r.id <> :runId")
    boolean existsOtherRunning(@Param("jobId") UUID jobId, @Param("runId") UUID runId);

    /** Waiting runs of one job; called with the job row already locked (lock order jobs, then runs). */
    @Query(value = """
            SELECT * FROM runs
             WHERE job_id = :jobId
               AND status = 'QUEUED'
               AND (:scheduledOnly = false OR trigger_type = 'SCHEDULED')
             FOR UPDATE
            """, nativeQuery = true)
    List<Run> lockQueuedRunsOfJob(@Param("jobId") UUID jobId, @Param("scheduledOnly") boolean scheduledOnly);

    /** Outbox: created but not yet published. */
    @Query(value = """
            SELECT * FROM runs
             WHERE status = 'QUEUED'
               AND dispatched_at IS NULL
             ORDER BY scheduled_time
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Run> lockUndispatched(@Param("limit") int limit);

    /** RUNNING well past timeout + grace: the worker that owned it is gone. */
    @Query(value = """
            SELECT * FROM runs
             WHERE status = 'RUNNING'
               AND started_at + make_interval(secs => timeout_seconds + :graceSeconds) < :now
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Run> lockStuckRunning(@Param("now") Instant now, @Param("graceSeconds") long graceSeconds,
                               @Param("limit") int limit);

    /** Still QUEUED long after its time: message lost, or no worker ever free. */
    @Query(value = """
            SELECT * FROM runs
             WHERE status = 'QUEUED'
               AND scheduled_time < :cutoff
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Run> lockExpiredQueued(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /**
     * Fast-path outbox marker used by the API after it publishes a manual run itself.
     * A single-column UPDATE, not load-and-save, so it never overwrites what the worker wrote. It does not
     * require QUEUED: a fast worker can start the run within milliseconds of the publish, before this runs,
     * and the run was still dispatched. Keyed by the full primary key, so it touches one partition.
     */
    @Modifying
    @Query("""
            UPDATE Run r SET r.dispatchedAt = :now
             WHERE r.id = :id AND r.scheduledTime = :scheduledTime AND r.dispatchedAt IS NULL
            """)
    int markDispatchedIfPending(@Param("id") UUID id, @Param("scheduledTime") Instant scheduledTime,
                                @Param("now") Instant now);
}
