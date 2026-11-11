package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.NotificationEventType;
import com.erp.ticketing.integration.notification.NotificationServiceProperties;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.sla.job.SlaMonitoringJob;
import com.erp.ticketing.sla.model.SlaPolicy;
import com.erp.ticketing.sla.repository.SlaPolicyRepository;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * Queues {@code ticket_reminder} WhatsApp messages for assignees of pending tickets, on the SLA monitoring
 * interval and with the SLA policies' own thresholds (see {@link ReminderPolicy}).
 *
 * <p>Duplicates are impossible by construction: each reminder's idempotency key is
 * {@code TICKET_REMINDER:{ticket}:{resolution round}:{type}:{assignee}:WHATSAPP}, unique in the outbox, and the
 * row is written with ON CONFLICT DO NOTHING. So a later scan, a second application instance scanning at the
 * same moment, or a restart all leave exactly one row per reminder. A new assignee gets their own reminders;
 * a reopened ticket (new resolution round) can be reminded about again. Whether a queued reminder still applies
 * (ticket not closed, still the same assignee) is checked again just before it is sent.
 *
 * <p>This job only reads tickets - it never saves them - so it cannot conflict with {@link SlaMonitoringJob}'s
 * updates to the same rows.
 */
@Component
public class WhatsAppReminderJob {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppReminderJob.class);

    private final NotificationServiceProperties notificationService;
    private final WhatsAppProperties whatsApp;
    private final TicketRepository tickets;
    private final SlaPolicyRepository policies;
    private final WhatsAppTemplateComposer composer;
    private final NotificationOutboxRepository outbox;
    private final TransactionTemplate tx;

    public WhatsAppReminderJob(NotificationServiceProperties notificationService, WhatsAppProperties whatsApp,
                               TicketRepository tickets, SlaPolicyRepository policies,
                               WhatsAppTemplateComposer composer, NotificationOutboxRepository outbox,
                               PlatformTransactionManager transactionManager) {
        this.notificationService = notificationService;
        this.whatsApp = whatsApp;
        this.tickets = tickets;
        this.policies = policies;
        this.composer = composer;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(transactionManager);
    }

    @Scheduled(fixedDelayString = "${app.whatsapp.reminders.fixed-delay-ms:${app.sla.monitoring.fixed-delay-ms:60000}}")
    public void scheduledTick() {
        try {
            scan(Instant.now());
        } catch (RuntimeException ex) {
            log.error("WhatsApp reminder scan failed", ex);
        }
    }

    /** @return how many reminder rows were newly queued */
    public int scan(Instant now) {
        if (!notificationService.isEnabled() || !whatsApp.isEnabled() || !whatsApp.getReminders().isEnabled()) {
            return 0;
        }
        Integer queued = tx.execute(status -> {
            List<Ticket> candidates = tickets.findByStatusNotInAndResolutionDueIsNotNull(SlaMonitoringJob.TERMINAL_STATUSES);
            Map<Long, Optional<SlaPolicy>> policyCache = new HashMap<>();
            int written = 0;
            for (Ticket ticket : candidates) {
                SlaPolicy policy = ticket.getSlaPolicyId() == null ? null
                        : policyCache.computeIfAbsent(ticket.getSlaPolicyId(), policies::findById).orElse(null);
                Optional<ReminderType> due = ReminderPolicy.due(ticket, policy, now, whatsApp.getReminders());
                if (due.isPresent()) {
                    written += queue(ticket, ticket.getAssignee(), due.get());
                }
            }
            return written;
        });
        int count = queued == null ? 0 : queued;
        if (count > 0) {
            log.info("Queued WhatsApp ticket reminders", kv("queued", count));
        }
        return count;
    }

    private int queue(Ticket ticket, User assignee, ReminderType type) {
        String eventKey = eventKey(ticket, type);
        // A cheap read first: most scans find reminders that were queued on an earlier tick.
        if (outbox.existsByIdempotencyKey(WhatsAppNotificationRecorder.idempotencyKey(eventKey, assignee.getId()))) {
            return 0;
        }
        int written = WhatsAppNotificationRecorder.record(outbox, composer, eventKey, NotificationEventType.TICKET_REMINDER,
                ticket.getId(), assignee, composer.ticketReminder(ticket, assignee, type));
        if (written > 0) {
            log.info("Recorded WhatsApp reminder", kv("ticketId", ticket.getId()), kv("reminder", type),
                    kv("userId", assignee.getId()));
        }
        return written;
    }

    static String eventKey(Ticket ticket, ReminderType type) {
        return "TICKET_REMINDER:" + ticket.getId() + ":" + ticket.getReopenCount() + ":" + type.name();
    }
}
