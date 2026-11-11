package com.erp.ticketing.integration.notification;

/**
 * PENDING: waiting for (another) hand-over attempt. ACCEPTED: the notification-service took the message - for
 * WhatsApp, Meta accepted it and returned a message id - this is NOT proof the recipient received it. FAILED:
 * permanent error or retries exhausted; eligible for a manual retry from the admin API. CANCELLED: deliberately
 * not sent because it no longer applied when its turn came (for example the ticket was closed or reassigned
 * before a WhatsApp reminder went out, or WhatsApp was switched off).
 */
public enum OutboxStatus {
    PENDING,
    ACCEPTED,
    FAILED,
    CANCELLED
}
