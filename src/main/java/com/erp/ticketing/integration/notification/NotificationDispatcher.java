package com.erp.ticketing.integration.notification;

import com.erp.ticketing.integration.notification.model.DeliveryAttempt;
import com.erp.ticketing.integration.notification.model.NotificationOutbox;
import com.erp.ticketing.integration.notification.repository.DeliveryAttemptRepository;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Hands committed outbox rows to the notification-service, with bounded exponential backoff.
 *
 * <p>Each tick: claim due rows (row locks with SKIP LOCKED plus a short lease, so several instances never
 * send the same row), call the notification-service <em>outside</em> any database transaction, then record
 * the outcome and the attempt in a second short transaction. Because rows only exist once the ticket change
 * has committed, a ticket operation can never be rolled back by - or wait on - a notification problem.
 *
 * <p>Outcomes: accepted rows are marked ACCEPTED; retryable failures are rescheduled with
 * {@code base * 2^(attempt-1)} (capped, and never sooner than a server's Retry-After); permanent failures
 * and exhausted retries are marked FAILED and stay eligible for {@link #retry(Long)}.
 */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);
    private static final int ERROR_LIMIT = 500;

    private final NotificationServiceProperties properties;
    private final NotificationOutboxRepository outbox;
    private final DeliveryAttemptRepository attempts;
    private final NotificationServiceClient client;
    private final TransactionTemplate tx;

    public NotificationDispatcher(NotificationServiceProperties properties,
                                  NotificationOutboxRepository outbox,
                                  DeliveryAttemptRepository attempts,
                                  NotificationServiceClient client,
                                  PlatformTransactionManager transactionManager) {
        this.properties = properties;
        this.outbox = outbox;
        this.attempts = attempts;
        this.client = client;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.notification-service.poll-interval-ms:5000}")
    public void scheduledTick() {
        try {
            dispatchDue();
        } catch (RuntimeException ex) {
            log.error("Notification dispatch tick failed", ex);
        }
    }

    /** @return how many rows were attempted */
    public int dispatchDue() {
        if (!properties.isEnabled()) {
            return 0;
        }
        Instant now = Instant.now();
        List<Long> ids = tx.execute(status -> {
            List<Long> due = outbox.lockDueIds(now, properties.getBatchSize());
            if (!due.isEmpty()) {
                outbox.lease(due, now.plus(properties.getLease()));
            }
            return due;
        });
        if (ids == null) {
            return 0;
        }
        for (Long id : ids) {
            deliver(id);
        }
        return ids.size();
    }

    private void deliver(Long id) {
        NotificationOutbox row = tx.execute(status -> outbox.findById(id).orElse(null));
        if (row == null || row.getStatus() != OutboxStatus.PENDING) {
            return;
        }
        String correlationId = row.getCorrelationId();
        if (correlationId != null) {
            MDC.put("correlationId", correlationId);
        }
        try {
            DeliveryOutcome outcome = send(row);
            tx.executeWithoutResult(status -> apply(id, outcome));
        } finally {
            MDC.remove("correlationId");
        }
    }

    private DeliveryOutcome send(NotificationOutbox row) {
        if (row.getChannel() != DeliveryChannel.EMAIL) {
            // Defensive: nothing produces other channels today (see DeliveryChannel).
            return DeliveryOutcome.permanent(null, "Channel " + row.getChannel() + " is not supported");
        }
        try {
            return client.sendEmail(new NotificationServiceClient.EmailRequest(
                    properties.getModule(), row.getEventType().name(), List.of(row.getRecipientEmail()),
                    row.getSubject(), row.getBodyText(), row.getBodyHtml(), row.getIdempotencyKey()),
                    row.getCorrelationId());
        } catch (RuntimeException ex) {
            return DeliveryOutcome.retryable(null, "Unexpected error: " + ex.getClass().getSimpleName(), null);
        }
    }

    private void apply(Long id, DeliveryOutcome outcome) {
        NotificationOutbox row = outbox.findById(id).orElse(null);
        if (row == null) {
            return;
        }
        int attemptNo = row.getAttempts() + 1;
        row.setAttempts(attemptNo);
        row.setLeaseUntil(null);

        DeliveryAttempt attempt = new DeliveryAttempt();
        attempt.setOutboxId(id);
        attempt.setAttemptNo(attemptNo);
        attempt.setHttpStatus(outcome.httpStatus());
        attempt.setDetail(truncate(outcome.detail()));

        switch (outcome.kind()) {
            case ACCEPTED -> {
                row.setStatus(OutboxStatus.ACCEPTED);
                row.setAcceptedAt(Instant.now());
                row.setLastError(null);
                attempt.setOutcome("ACCEPTED");
            }
            case PERMANENT -> {
                row.setStatus(OutboxStatus.FAILED);
                row.setLastError(truncate(outcome.detail()));
                attempt.setOutcome("PERMANENT_FAILURE");
                log.error("Notification failed permanently", kv("outboxId", id), kv("event", row.getEventType()),
                        kv("ticketId", row.getTicketId()), kv("status", outcome.httpStatus()));
            }
            case RETRYABLE -> {
                row.setLastError(truncate(outcome.detail()));
                if (attemptNo >= properties.getMaxAttempts()) {
                    row.setStatus(OutboxStatus.FAILED);
                    attempt.setOutcome("EXHAUSTED");
                    log.error("Notification retries exhausted", kv("outboxId", id), kv("event", row.getEventType()),
                            kv("ticketId", row.getTicketId()), kv("attempts", attemptNo));
                } else {
                    row.setNextAttemptAt(Instant.now().plus(backoff(attemptNo, outcome.retryAfter())));
                    attempt.setOutcome("RETRY_SCHEDULED");
                    log.warn("Notification will be retried", kv("outboxId", id), kv("attempt", attemptNo),
                            kv("status", outcome.httpStatus()));
                }
            }
        }
        outbox.save(row);
        attempts.save(attempt);
    }

    /** {@code base * 2^(attempt-1)}, capped at the maximum, and no sooner than the server asked. */
    Duration backoff(int attemptNo, Duration retryAfter) {
        long baseMillis = properties.getRetryBaseDelay().toMillis();
        long capMillis = properties.getRetryMaxDelay().toMillis();
        long millis = baseMillis;
        for (int i = 1; i < attemptNo && millis < capMillis; i++) {
            millis *= 2;
        }
        millis = Math.min(millis, capMillis);
        if (retryAfter != null) {
            millis = Math.max(millis, Math.min(retryAfter.toMillis(), capMillis));
        }
        return Duration.ofMillis(millis);
    }

    /**
     * Puts a FAILED row back in the queue with a fresh retry budget. The idempotency key is unchanged, so a
     * message the notification-service did already send is not sent twice within its dedupe window.
     *
     * @return false if the row does not exist or is not FAILED
     */
    public boolean retry(Long id) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            NotificationOutbox row = outbox.findById(id).orElse(null);
            if (row == null || row.getStatus() != OutboxStatus.FAILED) {
                return false;
            }
            row.setStatus(OutboxStatus.PENDING);
            row.setAttempts(0);
            row.setNextAttemptAt(Instant.now());
            row.setLeaseUntil(null);
            outbox.save(row);
            return true;
        }));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= ERROR_LIMIT ? value : value.substring(0, ERROR_LIMIT);
    }
}
