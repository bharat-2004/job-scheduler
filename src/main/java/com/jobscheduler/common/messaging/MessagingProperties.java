package com.jobscheduler.common.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * @param consumerTimeout how long a worker may hold an unacked message. Must exceed the
 *                        longest job timeout, because the worker acks only after the run finishes.
 * @param confirmTimeout  how long a publisher waits for broker confirms
 * @param retryMin        first back-off when a worker returns a message (e.g. database briefly down)
 * @param retryMax        longest back-off between returns
 */
@ConfigurationProperties("app.messaging")
public record MessagingProperties(
        @DefaultValue("runs") String exchange,
        @DefaultValue("runs.execute") String queue,
        @DefaultValue("90m") Duration consumerTimeout,
        @DefaultValue("10s") Duration confirmTimeout,
        @DefaultValue("2s") Duration retryMin,
        @DefaultValue("30s") Duration retryMax,
        @DefaultValue("50") int deliveryLimit) {
}
