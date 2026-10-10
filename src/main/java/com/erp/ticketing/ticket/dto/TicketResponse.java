package com.erp.ticketing.ticket.dto;

import java.time.Instant;
import java.util.Map;

public record TicketResponse(
        Long id,
        String ticketNumber,
        Long typeId,
        String typeName,
        Long categoryId,
        String categoryName,
        String title,
        String description,
        String priority,
        String status,
        String subStatus,
        Long requesterId,
        String requesterName,
        Long assigneeId,
        String assigneeName,
        Long departmentId,
        String departmentName,
        String source,
        Map<String, Object> tags,
        Map<String, Object> customFields,
        Instant createdAt,
        Instant updatedAt,
        Instant firstResponseDue,
        Instant resolutionDue,
        Instant firstResponseAt,
        Instant resolvedAt,
        Instant closedAt,
        String slaStatus,
        Long slaPolicyId
) {
}
