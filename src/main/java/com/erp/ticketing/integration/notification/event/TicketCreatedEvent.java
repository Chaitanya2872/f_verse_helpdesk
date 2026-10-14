package com.erp.ticketing.integration.notification.event;

import com.erp.ticketing.integration.notification.NotificationEventType;

public record TicketCreatedEvent(Long ticketId) implements TicketNotificationEvent {

    @Override
    public NotificationEventType type() {
        return NotificationEventType.TICKET_CREATED;
    }

    @Override
    public String dedupeKey() {
        return "TICKET_CREATED:" + ticketId;
    }
}
