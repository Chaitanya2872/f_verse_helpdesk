package com.erp.ticketing.integration.notification.event;

import com.erp.ticketing.integration.notification.NotificationEventType;

/**
 * The ticket moved into RESOLVED. {@code resolutionRound} is the ticket's reopen count at that moment, so a
 * ticket that is reopened and resolved again is a genuinely new event while a retry of the same one is not.
 */
public record TicketResolvedEvent(Long ticketId, int resolutionRound) implements TicketNotificationEvent {

    @Override
    public NotificationEventType type() {
        return NotificationEventType.TICKET_RESOLVED;
    }

    @Override
    public String dedupeKey() {
        return "TICKET_RESOLVED:" + ticketId + ":" + resolutionRound;
    }
}
