package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.DeliveryOutcome;
import com.erp.ticketing.integration.notification.NotificationEventType;
import com.erp.ticketing.integration.notification.NotificationServiceClient;
import com.erp.ticketing.integration.notification.NotificationServiceProperties;
import com.erp.ticketing.integration.notification.model.NotificationOutbox;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.integration.notification.whatsapp.WhatsAppTemplateComposer.StoredParams;
import com.erp.ticketing.sla.job.SlaMonitoringJob;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Delivers one WHATSAPP outbox row for the dispatcher, which calls it outside any transaction and records the
 * returned outcome exactly as for email (attempt log, bounded backoff, permanent failures not retried).
 *
 * <ol>
 *   <li><b>Still applies?</b> An assignment or reminder is cancelled - not sent - when, by the time it is due, the
 *       ticket is resolved, closed or cancelled, or has a different handler; a reminder also when its SLA clock has
 *       been paused. So nobody is told about a ticket they no longer hold, or reminded after closure.</li>
 *   <li><b>Phone number:</b> looked up in identity-service on the first attempt, normalised to international
 *       digits and kept on the row. A person with no usable number is a permanent failure (visible to admins);
 *       identity-service being down is retryable.</li>
 *   <li><b>Send</b> through the notification-service, which makes the Meta Cloud API call.</li>
 * </ol>
 */
@Component
public class WhatsAppDeliveryHandler {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppDeliveryHandler.class);

    private final WhatsAppProperties whatsApp;
    private final NotificationServiceProperties notificationService;
    private final NotificationOutboxRepository outbox;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final IdentityPhoneDirectory directory;
    private final WhatsAppTemplateComposer composer;
    private final NotificationServiceClient client;
    private final TransactionTemplate tx;

    public WhatsAppDeliveryHandler(WhatsAppProperties whatsApp, NotificationServiceProperties notificationService,
                                   NotificationOutboxRepository outbox, TicketRepository tickets, UserRepository users,
                                   IdentityPhoneDirectory directory, WhatsAppTemplateComposer composer,
                                   NotificationServiceClient client, PlatformTransactionManager transactionManager) {
        this.whatsApp = whatsApp;
        this.notificationService = notificationService;
        this.outbox = outbox;
        this.tickets = tickets;
        this.users = users;
        this.directory = directory;
        this.composer = composer;
        this.client = client;
        this.tx = new TransactionTemplate(transactionManager);
    }

    public DeliveryOutcome deliver(NotificationOutbox row) {
        if (!whatsApp.isEnabled()) {
            return DeliveryOutcome.cancelled("WhatsApp notifications are disabled");
        }
        String reason = tx.execute(status -> whyNotApplicable(row));
        if (reason != null) {
            log.info("WhatsApp notification no longer applies", kv("outboxId", row.getId()),
                    kv("event", row.getEventType()), kv("ticketId", row.getTicketId()), kv("reason", reason));
            return DeliveryOutcome.cancelled(reason);
        }

        String phone = row.getRecipientPhone();
        if (phone == null) {
            PhoneResult resolved = resolvePhone(row);
            if (resolved.failure() != null) {
                return resolved.failure();
            }
            phone = resolved.phone();
            String finalPhone = phone;
            // Kept on the row so a retry does not look it up again; the admin retry clears it to force a fresh lookup.
            row.setRecipientPhone(finalPhone);
            tx.executeWithoutResult(status -> outbox.setRecipientPhone(row.getId(), finalPhone));
        }

        StoredParams params;
        try {
            params = composer.fromJson(row.getTemplateParams());
        } catch (IllegalStateException ex) {
            return DeliveryOutcome.permanent(null, "Stored template parameters are unreadable");
        }
        return client.sendWhatsAppTemplate(new NotificationServiceClient.WhatsAppTemplateRequest(
                notificationService.getModule(), row.getEventType().name(), phone, row.getTemplateName(),
                row.getTemplateLanguage(), params.body(), params.buttons(), row.getIdempotencyKey()),
                row.getCorrelationId());
    }

    /** @return why the row must not be sent, or null when it still applies */
    private String whyNotApplicable(NotificationOutbox row) {
        if (row.getEventType() == NotificationEventType.TICKET_CREATED) {
            return null; // the requester is told about the creation even if the ticket moved on quickly
        }
        Ticket ticket = tickets.findById(row.getTicketId()).orElse(null);
        if (ticket == null) {
            return "Ticket no longer exists";
        }
        if (SlaMonitoringJob.TERMINAL_STATUSES.contains(ticket.getStatus())) {
            return "Ticket is " + ticket.getStatus();
        }
        User assignee = ticket.getAssignee();
        if (assignee == null || !Objects.equals(assignee.getId(), row.getRecipientUserId())) {
            return "Ticket was reassigned";
        }
        if (row.getEventType() == NotificationEventType.TICKET_REMINDER && ticket.getSlaPausedAt() != null) {
            return "SLA clock is paused";
        }
        return null;
    }

    private record PhoneResult(String phone, DeliveryOutcome failure) {
    }

    private PhoneResult resolvePhone(NotificationOutbox row) {
        String erpUserId = tx.execute(status -> users.findById(row.getRecipientUserId())
                .map(User::getErpUserId).orElse(null));
        if (erpUserId == null) {
            return new PhoneResult(null, DeliveryOutcome.permanent(null, "Recipient account no longer exists"));
        }
        IdentityPhoneDirectory.Lookup lookup = directory.lookup(erpUserId);
        switch (lookup.status()) {
            case UNAVAILABLE -> {
                return new PhoneResult(null, DeliveryOutcome.retryable(lookup.httpStatus(),
                        "Phone lookup failed: identity-service unavailable", null));
            }
            case MISCONFIGURED -> {
                return new PhoneResult(null, DeliveryOutcome.permanent(lookup.httpStatus(),
                        "Phone lookup not possible: identity-service URL or internal secret not configured or rejected"));
            }
            case NOT_FOUND -> {
                return new PhoneResult(null, DeliveryOutcome.permanent(lookup.httpStatus(),
                        "Recipient not found in identity-service"));
            }
            default -> {
                // FOUND
            }
        }
        String phone = PhoneNumbers.normalize(lookup.phone(), whatsApp.getDefaultCountryCode());
        if (phone == null) {
            log.info("WhatsApp recipient has no usable phone number", kv("outboxId", row.getId()),
                    kv("userId", row.getRecipientUserId()), kv("hasNumber", lookup.phone() != null));
            return new PhoneResult(null, DeliveryOutcome.permanent(null, lookup.phone() == null
                    ? "Recipient has no phone number on file"
                    : "Recipient's phone number is not a valid international number"));
        }
        return new PhoneResult(phone, null);
    }
}
