package com.erp.ticketing.sla.dto;

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
        Boolean isActive
) {
}
