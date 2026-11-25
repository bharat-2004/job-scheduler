package com.jobscheduler.common.messaging;

import java.util.UUID;

/**
 * The queue message: only identifiers. The worker loads everything else from the database,
 * so a message can never carry stale data (e.g. an old script version or timeout).
 */
public record RunMessage(UUID runId, UUID jobId) {
}
