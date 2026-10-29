package com.erp.ticketing.integration.notification;

/**
 * Channels a notification can be handed over on. Only {@link #EMAIL} is wired: the notification-service has
 * no WhatsApp endpoint yet (see docs/notification-integration.md), so no WHATSAPP row is ever produced.
 */
public enum DeliveryChannel {
    EMAIL,
    WHATSAPP
}
