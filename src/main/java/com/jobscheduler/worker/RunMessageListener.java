package com.jobscheduler.worker;

import com.jobscheduler.common.messaging.RunMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * N listener threads = N run slots. With prefetch 1 and the ack sent only after execute() returns,
 * RabbitMQ never hands a busy slot another message, so a run is never stuck behind another one.
 * An exception (only possible before the run is marked RUNNING) returns the message for a retry.
 */
@Component
@Profile("worker")
public class RunMessageListener {

    private final RunExecutionService executionService;

    public RunMessageListener(RunExecutionService executionService) {
        this.executionService = executionService;
    }

    @RabbitListener(queues = "${app.messaging.queue:runs.execute}", concurrency = "${app.worker.concurrency:5}")
    public void onMessage(RunMessage message) {
        executionService.execute(message);
    }
}
