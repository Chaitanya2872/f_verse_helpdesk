package com.erp.ticketing.ticket.dto;

/**
 * All fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest).
 */
public record TicketTypeUpdateRequest(
        String name,
        String description,
        String instructions,
        Long departmentId,
        Long serviceOwnerId,
        String lifecycleStatus,
        String audience,
        String keywords,
        String icon,
        String defaultConfidentiality
) {

    public TicketTypeUpdateRequest(String name, String description) {
        this(name, description, null, null, null, null, null, null, null, null);
    }
}
