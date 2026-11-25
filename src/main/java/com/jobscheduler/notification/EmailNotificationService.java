package com.jobscheduler.notification;

import com.jobscheduler.common.notification.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/** Sends delay alerts by email (Mailpit locally). Only the worker sends alerts. */
@Service
@Profile("worker")
public class EmailNotificationService implements NotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final JavaMailSender mailSender;
    private final String from;

    public EmailNotificationService(JavaMailSender mailSender,
                                    @Value("${app.notification.from:scheduler@jobscheduler.local}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    @Override
    public void sendDelayAlert(DelayAlert alert) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(alert.recipientEmail());
        message.setSubject("Job \"" + alert.jobName() + "\" started " + alert.delay().toMinutes() + " minutes late");
        message.setText("""
                Your job started later than scheduled.

                Job:        %s (%s)
                Run:        %s
                Scheduled:  %s
                Started:    %s
                Delay:      %d seconds

                This usually means all workers were busy or the scheduler was catching up.
                """.formatted(alert.jobName(), alert.jobId(), alert.runId(),
                alert.scheduledTime(), alert.startedAt(), alert.delay().toSeconds()));
        mailSender.send(message);
        log.info("delay alert sent for run {} ({}s late)", alert.runId(), alert.delay().toSeconds());
    }
}
