package com.erp.ticketing.integration.notification.whatsapp;

import com.erp.ticketing.integration.notification.DeliveryChannel;
import com.erp.ticketing.integration.notification.NotificationEventType;
import com.erp.ticketing.integration.notification.NotificationServiceProperties;
import com.erp.ticketing.integration.notification.event.TicketAssignedEvent;
import com.erp.ticketing.integration.notification.event.TicketNotificationEvent;
import com.erp.ticketing.integration.notification.repository.NotificationOutboxRepository;
import com.erp.ticketing.integration.notification.whatsapp.WhatsAppTemplateComposer.TemplateMessage;
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

import static net.logstash.logback.argument.StructuredArguments.kv;

/**
 * The WhatsApp half of the transactional outbox, alongside the email {@code TicketNotificationRecorder}: it runs
 * synchronously inside the transaction that changed the ticket, so its rows commit or roll back with that
 * change. Nothing here touches the network - the dispatcher sends committed rows, resolving the recipient's
 * phone number at that point.
 *
 * <ul>
 *   <li>ticket created: {@code ticket_created} to the requester; and if routing or a creation-time automation
 *       already gave the ticket a handler, {@code ticket_assigned} to that handler</li>
 *   <li>handler changed: {@code ticket_assigned} to the new handler (re-saving the same handler publishes no
 *       event, so sends nothing)</li>
 *   <li>resolved: no WhatsApp template exists for it, so nothing</li>
 * </ul>
 * Reminders are recorded by {@link WhatsAppReminderJob}. Failures are logged and swallowed so WhatsApp can never
 * fail a ticket operation; the only SQL used is an ON CONFLICT DO NOTHING insert, which cannot abort the
 * surrounding transaction.
 */
@Component
public class WhatsAppNotificationRecorder {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppNotificationRecorder.class);

    private final NotificationServiceProperties notificationService;
    private final WhatsAppProperties whatsApp;
    private final TicketRepository tickets;
    private final UserRepository users;
    private final WhatsAppTemplateComposer composer;
    private final NotificationOutboxRepository outbox;

    public WhatsAppNotificationRecorder(NotificationServiceProperties notificationService, WhatsAppProperties whatsApp,
                                        TicketRepository tickets, UserRepository users,
                                        WhatsAppTemplateComposer composer, NotificationOutboxRepository outbox) {
        this.notificationService = notificationService;
        this.whatsApp = whatsApp;
        this.tickets = tickets;
        this.users = users;
        this.composer = composer;
        this.outbox = outbox;
    }

    @EventListener
    public void on(TicketNotificationEvent event) {
        if (!notificationService.isEnabled() || !whatsApp.isEnabled()) {
            return;
        }
        try {
            record(event);
        } catch (RuntimeException ex) {
            log.error("Could not record WhatsApp notification", kv("event", event.type()),
                    kv("ticketId", event.ticketId()), ex);
        }
    }

    private void record(TicketNotificationEvent event) {
        Ticket ticket = tickets.findById(event.ticketId()).orElse(null);
        if (ticket == null) {
            log.warn("WhatsApp notification event for an unknown ticket", kv("ticketId", event.ticketId()));
            return;
        }
        switch (event.type()) {
            case TICKET_CREATED -> {
                User requester = ticket.getRequester();
                queue(event.dedupeKey(), NotificationEventType.TICKET_CREATED, ticket, requester,
                        requester == null ? null : composer.ticketCreated(ticket));
                User initialHandler = ticket.getAssignee();
                if (initialHandler != null) {
                    // Routing and creation-time automation assign without a TicketAssignedEvent.
                    queue("TICKET_ASSIGNED:" + ticket.getId() + ":" + initialHandler.getId() + ":initial",
                            NotificationEventType.TICKET_ASSIGNED, ticket, initialHandler,
                            composer.ticketAssigned(ticket, initialHandler, null));
                }
            }
            case TICKET_ASSIGNED -> {
                TicketAssignedEvent assigned = (TicketAssignedEvent) event;
                User assignee = users.findById(assigned.assigneeId()).orElse(null);
                User actor = assigned.assignedById() == null ? null : users.findById(assigned.assignedById()).orElse(null);
                queue(event.dedupeKey(), NotificationEventType.TICKET_ASSIGNED, ticket, assignee,
                        assignee == null ? null : composer.ticketAssigned(ticket, assignee, actor));
            }
            default -> {
                // No approved template for this event.
            }
        }
    }

    private void queue(String eventKey, NotificationEventType type, Ticket ticket, User recipient, TemplateMessage message) {
        if (recipient == null || message == null) {
            return;
        }
        if (!recipient.isActive()) {
            log.info("Skipping WhatsApp notification to an inactive account", kv("event", type),
                    kv("ticketId", ticket.getId()), kv("userId", recipient.getId()));
            return;
        }
        int written = record(outbox, composer, eventKey, type, ticket.getId(), recipient, message);
        log.info("Recorded WhatsApp notification", kv("event", type), kv("ticketId", ticket.getId()),
                kv("userId", recipient.getId()), kv("template", message.templateName()), kv("queued", written));
    }

    /** One WhatsApp outbox row; shared with the reminder job so both use the same key format. */
    static int record(NotificationOutboxRepository outbox, WhatsAppTemplateComposer composer, String eventKey,
                      NotificationEventType type, Long ticketId, User recipient, TemplateMessage message) {
        return outbox.insertWhatsAppIfAbsent(idempotencyKey(eventKey, recipient.getId()), type.name(), ticketId,
                recipient.getId(), recipient.getName(), message.templateName(), message.languageCode(),
                composer.toJson(message), MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    static String idempotencyKey(String eventKey, Long recipientUserId) {
        return eventKey + ":" + recipientUserId + ":" + DeliveryChannel.WHATSAPP;
    }
}
