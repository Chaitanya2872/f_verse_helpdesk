package com.erp.ticketing.ticket.dto;

import java.time.Instant;

public record ActivityLogResponse(
        Long id,
        Long ticketId,
        Long actorId,
        String actorName,
        String actionType,
        String fieldName,
        String oldValue,
        String newValue,
        Instant createdAt
) {
}
