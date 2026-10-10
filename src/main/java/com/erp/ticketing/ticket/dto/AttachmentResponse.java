package com.erp.ticketing.ticket.dto;

import java.time.Instant;

public record AttachmentResponse(
        Long id,
        Long ticketId,
        Long commentId,
        String fileName,
        String fileType,
        Long fileSizeBytes,
        Long uploadedBy,
        String uploadedByName,
        Instant createdAt,
        String downloadUrl
) {
}
