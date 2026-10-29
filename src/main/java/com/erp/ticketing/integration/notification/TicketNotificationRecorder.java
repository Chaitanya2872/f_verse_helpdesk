package com.erp.ticketing.integration.notification;

import com.erp.ticketing.integration.notification.RecipientResolver.Recipient;
import com.erp.ticketing.integration.notification.TicketEmailComposer.Extras;
import com.erp.ticketing.integration.notification.TicketEmailComposer.Message;
import com.erp.ticketing.integration.notification.event.TicketAssignedEvent;
import com.erp.ticketing.integration.notification.event.TicketNotificationEvent;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.security.CorrelationIdFilter;
import com.erp.ticketing.ticket.model.Ticket;
import com.erp.ticketing.ticket.repository.TicketRepository;
import com.erp.ticketing.user.model.User;
import com.erp.ticketing.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The transactional-outbox half of the integration. It runs synchronously inside the transaction that
 * published the event, so the outbox rows are committed or rolled back together with the ticket change:
 * a rolled-back change leaves no row, and a committed one cannot lose its notification to a crash.
 * Nothing here talks to the network; {@link NotificationDispatcher} sends the rows once they are committed.
 *
 * <p>Failures are logged and swallowed so that a problem with notifications can never fail the ticket
 * operation itself. The statements used cannot abort the transaction (the insert is ON CONFLICT DO NOTHING).
 */
@Component
public class TicketNotificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(TicketNotificationRecorder.class);

    private final NotificationServiceProperties properties;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final RecipientResolver recipientResolver;
    private final TicketEmailComposer composer;
    private final NotificationOutboxRepository outbox;

    public TicketNotificationRecorder(NotificationServiceProperties properties,
                                      TicketRepository ticketRepository,
                                      UserRepository userRepository,
                                      RecipientResolver recipientResolver,
                                      TicketEmailComposer composer,
                                      NotificationOutboxRepository outbox) {
        this.properties = properties;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.recipientResolver = recipientResolver;
        this.composer = composer;
        this.outbox = outbox;
    }

    @EventListener
    public void on(TicketNotificationEvent event) {
        if (!properties.isEnabled()) {
            return;
        }
        try {
            record(event);
        } catch (RuntimeException ex) {
            log.error("Could not record notification event", kv("event", event.type()),
                    kv("ticketId", event.ticketId()), ex);
        }
    }

    private void record(TicketNotificationEvent event) {
        Ticket ticket = ticketRepository.findById(event.ticketId()).orElse(null);
        if (ticket == null) {
            log.warn("Notification event for an unknown ticket", kv("ticketId", event.ticketId()));
            return;
        }

        User assignee = null;
        Extras extras = Extras.none();
        if (event instanceof TicketAssignedEvent assigned) {
            assignee = userRepository.findById(assigned.assigneeId()).orElse(null);
            User actor = assigned.assignedById() == null ? null : userRepository.findById(assigned.assignedById()).orElse(null);
            extras = new Extras(assignee, actor, assigned.assignedAt());
        } else if (event.type() == NotificationEventType.TICKET_RESOLVED) {
            extras = new Extras(null, null, Instant.now());
        }

        List<Recipient> recipients = recipientResolver.resolve(event.type(), ticket, assignee);
        if (recipients.isEmpty()) {
            log.info("No recipients for notification event", kv("event", event.type()), kv("ticketId", ticket.getId()));
            return;
        }

        Message message = composer.compose(event.type(), ticket, extras);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        int written = 0;
        for (Recipient recipient : recipients) {
            String key = event.dedupeKey() + ":" + recipient.userId() + ":" + DeliveryChannel.EMAIL;
            written += outbox.insertIfAbsent(key, event.type().name(), DeliveryChannel.EMAIL.name(), ticket.getId(),
                    recipient.userId(), recipient.name(), recipient.email(), message.subject(), message.html(),
                    message.text(), correlationId);
        }
        log.info("Recorded notification event", kv("event", event.type()), kv("ticketId", ticket.getId()),
                kv("recipients", recipients.size()), kv("queued", written));
    }
}
