package com.erp.ticketing.sla.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record SlaPolicyCreateRequest(
        @NotBlank String name,
        String description,
        Map<String, Object> conditionsJson,
        @NotNull Integer firstResponseMinutes,
        @NotNull Integer resolutionMinutes,
        Long calendarId,
        Boolean isActive
) {
}
