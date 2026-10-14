package com.erp.ticketing.integration.notification.event;

import com.erp.ticketing.integration.notification.NotificationEventType;

import java.time.Instant;

/** The handler actually changed to {@code assigneeId}; {@code assignedById} is who made the change. */
public record TicketAssignedEvent(Long ticketId, Long assigneeId, Long assignedById, Instant assignedAt)
        implements TicketNotificationEvent {

    @Override
    public NotificationEventType type() {
        return NotificationEventType.TICKET_ASSIGNED;
    }

    @Override
    public String dedupeKey() {
        return "TICKET_ASSIGNED:" + ticketId + ":" + assigneeId + ":" + assignedAt.toEpochMilli();
    }
}
