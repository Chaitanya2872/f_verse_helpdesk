package com.erp.ticketing.sla.dto;

import java.util.List;
import java.util.Map;

/**
 * All fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest).
 */
public record SlaPolicyUpdateRequest(
        String name,
        String description,
        Map<String, Object> conditionsJson,
        Integer firstResponseMinutes,
        Integer resolutionMinutes,
        Long calendarId,
        Boolean isActive,
        List<String> pauseStatuses,
        Integer warningPercent,
        Integer escalateAfterBreachMinutes
) {

    public SlaPolicyUpdateRequest(String name, String description, Map<String, Object> conditionsJson,
                                  Integer firstResponseMinutes, Integer resolutionMinutes, Long calendarId,
                                  Boolean isActive) {
        this(name, description, conditionsJson, firstResponseMinutes, resolutionMinutes, calendarId, isActive,
                null, null, null);
    }
}
