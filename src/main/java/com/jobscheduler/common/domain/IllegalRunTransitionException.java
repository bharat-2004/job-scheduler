package com.jobscheduler.common.domain;

/** A late or duplicate message tried to move a run backwards or out of a final state. */
public class IllegalRunTransitionException extends DomainException {
    public IllegalRunTransitionException(String message) {
        super(message);
    }
}
