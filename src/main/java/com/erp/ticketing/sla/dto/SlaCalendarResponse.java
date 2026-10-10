package com.erp.ticketing.sla.dto;

import java.util.Map;

public record SlaCalendarResponse(Long id, String name, String timezone, Map<String, Object> workingHoursJson) {
}
