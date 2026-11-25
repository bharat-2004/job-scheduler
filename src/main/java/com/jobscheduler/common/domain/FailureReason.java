package com.jobscheduler.common.domain;

/**
 * Why a run ended FAILED. Each reason is only valid from one starting status:
 * a run that never started cannot have a SCRIPT_ERROR, and a running one cannot be SYSTEM_DOWN.
 */
public enum FailureReason {
    // From RUNNING: the script started
    SCRIPT_ERROR(RunStatus.RUNNING),
    WORKER_CRASHED(RunStatus.RUNNING),
    INFRASTRUCTURE_ERROR(RunStatus.RUNNING),

    // From QUEUED: the script never started
    SYSTEM_DOWN(RunStatus.QUEUED),
    PREVIOUS_RUN_IN_PROGRESS(RunStatus.QUEUED),
    /** Added in implementation: job disabled or deleted while this run was waiting. */
    JOB_DISABLED(RunStatus.QUEUED),
    /** Added in implementation: still QUEUED long after its time (message lost, or no free worker). */
    EXPIRED_IN_QUEUE(RunStatus.QUEUED);

    private final RunStatus allowedFrom;

    FailureReason(RunStatus allowedFrom) {
        this.allowedFrom = allowedFrom;
    }

    public RunStatus allowedFrom() {
        return allowedFrom;
    }
}
