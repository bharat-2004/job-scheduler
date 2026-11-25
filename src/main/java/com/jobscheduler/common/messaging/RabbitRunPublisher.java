package com.jobscheduler.common.messaging;

import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.List;

/**
 * Publishes runs with publisher confirms: {@link #publishAll} returns only once the broker has
 * safely stored every message (for a quorum queue: replicated to a majority), otherwise it throws
 * and the caller's transaction rolls back, leaving the runs undispatched for the next attempt.
 */
@Component
public class RabbitRunPublisher implements RunPublisher {

    /** RabbitMQ 4.4+ quorum-queue delayed delivery: epoch millis as a Long. */
    static final String DELIVERY_TIME_HEADER = "x-opt-delivery-time";

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties props;
    private final Clock clock;

    public RabbitRunPublisher(RabbitTemplate rabbitTemplate, MessagingProperties props, Clock clock) {
        this.rabbitTemplate = rabbitTemplate;
        this.props = props;
        this.clock = clock;
    }

    @Override
    public void publishAll(List<Delivery> deliveries) {
        if (deliveries.isEmpty()) {
            return;
        }
        long nowMillis = clock.millis();
        rabbitTemplate.invoke(ops -> {
            for (Delivery d : deliveries) {
                long deliverAtMillis = d.deliverAt().toEpochMilli();
                ops.convertAndSend(props.exchange(), props.queue(), new RunMessage(d.runId(), d.jobId()), message -> {
                    message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                    message.getMessageProperties().setMessageId(d.runId().toString());
                    if (deliverAtMillis > nowMillis) {
                        message.getMessageProperties().setHeader(DELIVERY_TIME_HEADER, Long.valueOf(deliverAtMillis));
                    }
                    return message;
                });
            }
            ops.waitForConfirmsOrDie(props.confirmTimeout().toMillis());
            return null;
        });
    }
}
