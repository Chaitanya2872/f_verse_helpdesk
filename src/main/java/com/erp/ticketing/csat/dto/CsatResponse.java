package com.erp.ticketing.csat.dto;

import java.time.Instant;

public record CsatResponse(
        Long id,
        Long ticketId,
        String ticketNumber,
        Long requesterId,
        String requesterName,
        Integer rating,
        String comment,
        String status,
        Instant createdAt,
        Instant submittedAt
) {
}
