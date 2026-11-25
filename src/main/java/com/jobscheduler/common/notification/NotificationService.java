package com.jobscheduler.common.notification;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tells a user something about their job. Lives in common so the worker depends only on this
 * contract; the email implementation sits in the notification module.
 */
public interface NotificationService {

    void sendDelayAlert(DelayAlert alert);

    record DelayAlert(String recipientEmail, UUID jobId, String jobName, UUID runId,
                      Instant scheduledTime, Instant startedAt, Duration delay) {
    }
}
