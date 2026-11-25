package com.jobscheduler.common.messaging;

import com.jobscheduler.common.domain.Job;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Queue topology, declared by whichever role connects first.
 *
 * <p>Design change from the HLD: the rabbitmq-delayed-message-exchange plugin is no longer
 * maintained (it depends on Mnesia, removed in RabbitMQ 4.3). RabbitMQ 4.4 quorum queues
 * support delayed delivery natively via the {@code x-opt-delivery-time} header, and quorum
 * queues are replicated, so delayed messages are no longer tied to one node.
 */
@Configuration
public class MessagingConfig {

    @Bean
    public DirectExchange runsExchange(MessagingProperties props) {
        return ExchangeBuilder.directExchange(props.exchange()).durable(true).build();
    }

    @Bean
    public Queue runsQueue(MessagingProperties props) {
        if (props.consumerTimeout().compareTo(Job.MAX_TIMEOUT) <= 0) {
            throw new IllegalStateException("app.messaging.consumer-timeout must exceed the max job timeout "
                    + Job.MAX_TIMEOUT + ", or long runs would be redelivered while still executing");
        }
        return QueueBuilder.durable(props.queue())
                .quorum()
                .deliveryLimit(props.deliveryLimit())
                .withArgument("x-consumer-timeout", props.consumerTimeout().toMillis())
                // Messages a worker returns (e.g. DB briefly down before markRunning) come back with
                // linear back-off instead of a hot redelivery loop.
                .withArgument("x-delayed-retry-type", "all")
                .withArgument("x-delayed-retry-min", props.retryMin().toMillis())
                .withArgument("x-delayed-retry-max", props.retryMax().toMillis())
                .build();
    }

    @Bean
    public Binding runsBinding(Queue runsQueue, DirectExchange runsExchange, MessagingProperties props) {
        return BindingBuilder.bind(runsQueue).to(runsExchange).with(props.queue());
    }

    @Bean
    public MessageConverter messageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
