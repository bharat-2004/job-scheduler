package com.jobscheduler.common.domain;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Composite primary key of runs. PostgreSQL requires the partition key (scheduled_time)
 * to be part of every unique index on a partitioned table, including the primary key.
 */
public class RunId implements Serializable {

    private UUID id;
    private Instant scheduledTime;

    protected RunId() {
    }

    public RunId(UUID id, Instant scheduledTime) {
        this.id = id;
        this.scheduledTime = scheduledTime;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RunId other)) return false;
        return Objects.equals(id, other.id) && Objects.equals(scheduledTime, other.scheduledTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, scheduledTime);
    }
}
