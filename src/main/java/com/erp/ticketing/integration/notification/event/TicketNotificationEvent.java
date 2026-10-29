package com.erp.ticketing.integration.notification.event;

import com.erp.ticketing.integration.notification.NotificationEventType;

/**
 * Published by the ticket service after a lifecycle change has been applied inside its transaction.
 * The recorder turns it into outbox rows in that same transaction; nothing leaves the process until the
 * transaction has committed and the dispatcher picks the rows up.
 */
public interface TicketNotificationEvent {

    Long ticketId();

    NotificationEventType type();

    /**
     * Stable identity of this occurrence. Processing the same event twice yields the same key, so the
     * outbox's unique constraint makes it a no-op instead of a duplicate message.
     */
    String dedupeKey();
}
