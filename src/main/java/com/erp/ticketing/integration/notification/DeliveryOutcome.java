package com.erp.ticketing.integration.notification;

import java.time.Duration;

/**
 * Result of one hand-over attempt, reduced to what the dispatcher needs to decide what happens next.
 *
 * @param kind       ACCEPTED: the notification-service took it. RETRYABLE: timeout, I/O error, 408, 429 or 5xx.
 *                   PERMANENT: 400/401/403/other 4xx - retrying the same request cannot help.
 * @param httpStatus status code, or null when no response arrived
 * @param detail     short, secret-free explanation
 * @param retryAfter the server's Retry-After, if it sent one
 */
public record DeliveryOutcome(Kind kind, Integer httpStatus, String detail, Duration retryAfter) {

    public enum Kind {
        ACCEPTED,
        RETRYABLE,
        PERMANENT
    }

    public static DeliveryOutcome accepted(int status, String detail) {
        return new DeliveryOutcome(Kind.ACCEPTED, status, detail, null);
    }

    public static DeliveryOutcome retryable(Integer status, String detail, Duration retryAfter) {
        return new DeliveryOutcome(Kind.RETRYABLE, status, detail, retryAfter);
    }

    public static DeliveryOutcome permanent(Integer status, String detail) {
        return new DeliveryOutcome(Kind.PERMANENT, status, detail, null);
    }
}
