package com.jobscheduler.common.repository;

import com.jobscheduler.common.domain.Job;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JobRepository extends JpaRepository<Job, UUID>, InsertSupport {

    /** Ownership is part of the query: another user's job is simply "not found". */
    Optional<Job> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

    Page<Job> findByOwnerIdAndDeletedAtIsNull(UUID ownerId, Pageable pageable);

    boolean existsByOwnerIdAndNameAndDeletedAtIsNull(UUID ownerId, String name);

    boolean existsByOwnerIdAndNameAndDeletedAtIsNullAndIdNot(UUID ownerId, String name, UUID id);

    /** Row lock on one job, waiting if needed. Includes deleted jobs so the worker can see them. */
    @Query(value = "SELECT * FROM jobs WHERE id = :id FOR UPDATE", nativeQuery = true)
    Optional<Job> lockById(@Param("id") UUID id);

    /**
     * The scheduler's due-jobs query. SKIP LOCKED lets two scheduler instances split the work:
     * rows one instance holds are invisible to the other instead of making it wait.
     */
    @Query(value = """
            SELECT * FROM jobs
             WHERE next_run_time < :windowEnd
               AND enabled
               AND deleted_at IS NULL
             ORDER BY next_run_time
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Job> lockDueJobs(@Param("windowEnd") Instant windowEnd, @Param("limit") int limit);
}
