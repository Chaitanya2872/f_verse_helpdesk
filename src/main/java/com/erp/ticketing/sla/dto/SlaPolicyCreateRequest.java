package com.erp.ticketing.sla.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record SlaPolicyCreateRequest(
        @NotBlank String name,
        String description,
        Map<String, Object> conditionsJson,
        @NotNull Integer firstResponseMinutes,
        @NotNull Integer resolutionMinutes,
        Long calendarId,
        Boolean isActive,
        List<String> pauseStatuses,
        Integer warningPercent,
        Integer escalateAfterBreachMinutes
) {

    public SlaPolicyCreateRequest(String name, String description, Map<String, Object> conditionsJson,
                                  Integer firstResponseMinutes, Integer resolutionMinutes, Long calendarId,
                                  Boolean isActive) {
        this(name, description, conditionsJson, firstResponseMinutes, resolutionMinutes, calendarId, isActive,
                null, null, null);
    }
}
