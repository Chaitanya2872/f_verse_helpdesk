package com.erp.ticketing.sla.dto;

import java.util.List;
import java.util.Map;

public record SlaPolicyResponse(
        Long id,
        String name,
        String description,
        Map<String, Object> conditionsJson,
        int firstResponseMinutes,
        int resolutionMinutes,
        Long calendarId,
        boolean isActive,
        int version,
        List<String> pauseStatuses,
        int warningPercent,
        Integer escalateAfterBreachMinutes
) {
}
