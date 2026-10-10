package com.erp.ticketing.ticket.dto;

import java.time.Instant;

public record CommentResponse(
        Long id,
        Long ticketId,
        Long authorId,
        String authorName,
        String body,
        boolean isInternal,
        Instant createdAt
) {
}
