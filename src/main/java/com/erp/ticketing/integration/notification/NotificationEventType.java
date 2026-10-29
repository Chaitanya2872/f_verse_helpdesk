package com.erp.ticketing.integration.notification;

/** The ticket lifecycle events that raise notifications through the notification-service. */
public enum NotificationEventType {
    TICKET_CREATED,
    TICKET_ASSIGNED,
    TICKET_RESOLVED
}
