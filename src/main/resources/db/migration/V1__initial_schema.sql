-- Job Scheduler schema (LLD "Database schema"). All timestamps are TIMESTAMPTZ and written in UTC.

CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE jobs (
    id              UUID         PRIMARY KEY,
    owner_id        UUID         NOT NULL REFERENCES users (id),
    name            VARCHAR(100) NOT NULL,
    language        VARCHAR(20)  NOT NULL,
    script_version  INT          NOT NULL,
    schedule_type   VARCHAR(10)  NOT NULL,
    cron_expression VARCHAR(100),
    run_at          TIMESTAMPTZ,
    timezone        VARCHAR(50)  NOT NULL DEFAULT 'UTC',
    timeout_seconds INT          NOT NULL,
    enabled         BOOLEAN      NOT NULL DEFAULT true,
    next_run_time   TIMESTAMPTZ,
    version         INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,

    CONSTRAINT jobs_language_valid       CHECK (language IN ('PYTHON')),
    CONSTRAINT jobs_schedule_type_valid  CHECK (schedule_type IN ('ONE_TIME', 'CRON')),
    CONSTRAINT jobs_cron_has_expression  CHECK (schedule_type <> 'CRON' OR cron_expression IS NOT NULL),
    CONSTRAINT jobs_one_time_has_run_at  CHECK (schedule_type <> 'ONE_TIME' OR run_at IS NOT NULL),
    CONSTRAINT jobs_timeout_positive     CHECK (timeout_seconds > 0),
    CONSTRAINT jobs_script_version_valid CHECK (script_version >= 1),
    -- A disabled or deleted job has nothing scheduled.
    CONSTRAINT jobs_inactive_not_scheduled CHECK ((enabled AND deleted_at IS NULL) OR next_run_time IS NULL)
);

-- Name is unique per owner among live jobs, so a deleted job's name can be reused.
-- Leading owner_id also serves "list my jobs".
CREATE UNIQUE INDEX ux_jobs_owner_name ON jobs (owner_id, name) WHERE deleted_at IS NULL;

-- The scheduler's "due jobs" query only ever looks at enabled, live jobs.
CREATE INDEX ix_jobs_due ON jobs (next_run_time) WHERE enabled AND deleted_at IS NULL;


-- runs is partitioned by day on scheduled_time: 30-day retention becomes a DROP TABLE, not a big DELETE.
CREATE TABLE runs (
    id              UUID         NOT NULL,
    job_id          UUID         NOT NULL REFERENCES jobs (id),
    trigger_type    VARCHAR(10)  NOT NULL,
    status          VARCHAR(10)  NOT NULL,
    scheduled_time  TIMESTAMPTZ  NOT NULL,
    dispatched_at   TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    script_version  INT          NOT NULL,
    timeout_seconds INT          NOT NULL,
    exit_code       INT,
    failure_reason  VARCHAR(30),
    failure_detail  VARCHAR(500),
    log_path        VARCHAR(500),
    worker_id       VARCHAR(100),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    -- Every unique index on a partitioned table must contain the partition key.
    PRIMARY KEY (id, scheduled_time),

    CONSTRAINT runs_trigger_type_valid CHECK (trigger_type IN ('SCHEDULED', 'MANUAL')),
    CONSTRAINT runs_status_valid CHECK (status IN ('QUEUED', 'RUNNING', 'SUCCESS', 'FAILED', 'TIMED_OUT')),
    CONSTRAINT runs_failure_reason_valid CHECK (failure_reason IN (
        'SCRIPT_ERROR', 'WORKER_CRASHED', 'INFRASTRUCTURE_ERROR',
        'SYSTEM_DOWN', 'PREVIOUS_RUN_IN_PROGRESS', 'JOB_DISABLED', 'EXPIRED_IN_QUEUE')),
    -- Backstops for the domain state machine.
    CONSTRAINT runs_failed_iff_reason CHECK ((status = 'FAILED') = (failure_reason IS NOT NULL)),
    CONSTRAINT runs_started_has_worker CHECK (started_at IS NULL OR worker_id IS NOT NULL),
    CONSTRAINT runs_timeout_positive CHECK (timeout_seconds > 0)
) PARTITION BY RANGE (scheduled_time);

-- Run history of a job, newest first.
CREATE INDEX ix_runs_job_history ON runs (job_id, scheduled_time DESC);

-- Duplicate guard: a job can have only one SCHEDULED run per scheduled time,
-- whatever the application code does. Manual runs are not limited.
CREATE UNIQUE INDEX ux_runs_scheduled_once ON runs (job_id, scheduled_time) WHERE trigger_type = 'SCHEDULED';

-- Dispatcher, sweeper and "is something running" checks only look at active runs.
CREATE INDEX ix_runs_active ON runs (status, scheduled_time) WHERE status IN ('QUEUED', 'RUNNING');


-- Creates one partition per UTC day from (today - retention_days) to (today + days_ahead)
-- and drops partitions older than the retention window. Idempotent; an advisory lock stops
-- two instances from running it at the same moment.
CREATE OR REPLACE FUNCTION maintain_run_partitions(retention_days INT, days_ahead INT)
RETURNS VOID
LANGUAGE plpgsql AS
$$
DECLARE
    today DATE := (now() AT TIME ZONE 'UTC')::date;
    d     DATE;
    part  RECORD;
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext('maintain_run_partitions'));

    FOR d IN SELECT generate_series(today - retention_days, today + days_ahead, INTERVAL '1 day')::date
    LOOP
        EXECUTE format(
            'CREATE TABLE IF NOT EXISTS %I PARTITION OF runs FOR VALUES FROM (%L) TO (%L)',
            'runs_' || to_char(d, 'YYYYMMDD'),
            d::timestamp AT TIME ZONE 'UTC',
            (d + 1)::timestamp AT TIME ZONE 'UTC');
    END LOOP;

    FOR part IN
        SELECT c.relname
          FROM pg_inherits i
          JOIN pg_class c ON c.oid = i.inhrelid
          JOIN pg_class p ON p.oid = i.inhparent
         WHERE p.relname = 'runs'
           AND c.relname ~ '^runs_[0-9]{8}$'
           AND to_date(substring(c.relname FROM 6), 'YYYYMMDD') < today - retention_days
    LOOP
        EXECUTE format('DROP TABLE %I', part.relname);
    END LOOP;
END;
$$;
