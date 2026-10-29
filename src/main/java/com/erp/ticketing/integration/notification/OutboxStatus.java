package com.erp.ticketing.integration.notification;

/**
 * PENDING: waiting for (another) hand-over attempt. ACCEPTED: the notification-service took the message -
 * this is NOT proof the recipient received it. FAILED: permanent error or retries exhausted; eligible for a
 * manual retry from the admin API.
 */
public enum OutboxStatus {
    PENDING,
    ACCEPTED,
    FAILED
}
