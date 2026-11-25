package com.jobscheduler.common.messaging;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Puts runs on the execution queue. Implemented by RabbitMQ; the services never see AMQP types. */
public interface RunPublisher {

    /**
     * Publishes every delivery and returns only after the broker confirmed all of them,
     * or throws. A delivery whose {@code deliverAt} is in the future stays invisible to
     * workers until then.
     */
    void publishAll(List<Delivery> deliveries);

    record Delivery(UUID runId, UUID jobId, Instant deliverAt) {
    }
}
