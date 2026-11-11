package com.erp.ticketing.integration.notification;

/**
 * The ticket lifecycle events that raise notifications through the notification-service.
 * {@link #TICKET_REMINDER} is WhatsApp-only: it is raised by the SLA reminder job, not by a ticket change.
 */
public enum NotificationEventType {
    TICKET_CREATED,
    TICKET_ASSIGNED,
    TICKET_RESOLVED,
    TICKET_REMINDER
}
