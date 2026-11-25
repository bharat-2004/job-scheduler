package com.jobscheduler.common.domain;

/** Bad cron, bad timezone, timeout longer than the schedule interval, first run too soon. Maps to 400. */
public class InvalidScheduleException extends DomainException {
    public InvalidScheduleException(String message) {
        super(message);
    }
}
