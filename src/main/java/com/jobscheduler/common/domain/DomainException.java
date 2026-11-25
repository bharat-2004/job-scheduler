package com.jobscheduler.common.domain;

/** Base class for business-rule violations raised by the domain model. */
public abstract class DomainException extends RuntimeException {
    protected DomainException(String message) {
        super(message);
    }
}
