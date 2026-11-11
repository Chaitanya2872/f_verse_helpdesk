package com.erp.ticketing.integration.notification;

import java.time.Duration;

/**
 * Result of one hand-over attempt, reduced to what the dispatcher needs to decide what happens next.
 *
 * @param kind              ACCEPTED: the notification-service took it. RETRYABLE: timeout, I/O error, 408, 429 or
 *                          5xx. PERMANENT: 400/401/403/422/other 4xx - retrying the same request cannot help.
 *                          CANCELLED: deliberately not sent because it no longer applies.
 * @param httpStatus        status code, or null when no response arrived
 * @param detail            short, secret-free explanation
 * @param retryAfter        the server's Retry-After, if it sent one
 * @param providerMessageId the provider's id for an accepted message (WhatsApp's {@code wamid...}), if any
 */
public record DeliveryOutcome(Kind kind, Integer httpStatus, String detail, Duration retryAfter,
                              String providerMessageId) {

    public enum Kind {
        ACCEPTED,
        RETRYABLE,
        PERMANENT,
        CANCELLED
    }

    public DeliveryOutcome(Kind kind, Integer httpStatus, String detail, Duration retryAfter) {
        this(kind, httpStatus, detail, retryAfter, null);
    }

    public static DeliveryOutcome accepted(int status, String detail) {
        return new DeliveryOutcome(Kind.ACCEPTED, status, detail, null, null);
    }

    public static DeliveryOutcome accepted(int status, String detail, String providerMessageId) {
        return new DeliveryOutcome(Kind.ACCEPTED, status, detail, null, providerMessageId);
    }

    public static DeliveryOutcome retryable(Integer status, String detail, Duration retryAfter) {
        return new DeliveryOutcome(Kind.RETRYABLE, status, detail, retryAfter, null);
    }

    public static DeliveryOutcome permanent(Integer status, String detail) {
        return new DeliveryOutcome(Kind.PERMANENT, status, detail, null, null);
    }

    public static DeliveryOutcome cancelled(String reason) {
        return new DeliveryOutcome(Kind.CANCELLED, null, reason, null, null);
    }
}
