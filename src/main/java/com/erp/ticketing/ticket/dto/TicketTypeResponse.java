package com.erp.ticketing.ticket.dto;

public record TicketTypeResponse(
        Long id,
        String name,
        String description,
        String instructions,
        Long departmentId,
        Long serviceOwnerId,
        String lifecycleStatus,
        String audience,
        String keywords,
        String icon,
        String defaultConfidentiality,
        boolean hasForm
) {
}
