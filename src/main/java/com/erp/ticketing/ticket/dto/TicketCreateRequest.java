package com.erp.ticketing.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record TicketCreateRequest(
        @NotNull Long typeId,
        Long categoryId,
        @NotBlank String title,
        String description,
        @NotBlank String priority,
        Map<String, Object> tags,
        Map<String, Object> customFields
) {
}
