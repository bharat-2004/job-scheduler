package com.jobscheduler.common.domain;

import java.util.Set;

/**
 * A run only moves forward: QUEUED -> RUNNING -> one final state,
 * or QUEUED -> FAILED when it never starts.
 */
public enum RunStatus {
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    TIMED_OUT;

    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == TIMED_OUT;
    }

    public boolean canTransitionTo(RunStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<RunStatus> allowedTargets() {
        return switch (this) {
            case QUEUED -> Set.of(RUNNING, FAILED);
            case RUNNING -> Set.of(SUCCESS, FAILED, TIMED_OUT);
            case SUCCESS, FAILED, TIMED_OUT -> Set.of();
        };
    }
}
