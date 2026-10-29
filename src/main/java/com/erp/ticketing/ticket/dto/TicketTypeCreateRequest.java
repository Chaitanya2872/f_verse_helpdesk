package com.erp.ticketing.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record TicketTypeCreateRequest(
        @NotBlank String name,
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

    public TicketTypeCreateRequest(String name, String description) {
        this(name, description, null, null, null, null, null, null, null, null);
    }
}
