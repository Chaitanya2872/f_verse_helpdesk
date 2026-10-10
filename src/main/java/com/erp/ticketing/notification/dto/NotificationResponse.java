package com.erp.ticketing.notification.dto;

import java.time.Instant;

public record NotificationResponse(
        Long id,
        Long ticketId,
        String type,
        String eventType,
        String subject,
        String body,
        boolean isRead,
        Instant createdAt
) {
}
