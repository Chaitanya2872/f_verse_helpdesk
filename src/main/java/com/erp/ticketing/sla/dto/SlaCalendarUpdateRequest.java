package com.erp.ticketing.sla.dto;

import java.util.Map;

/**
 * All fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest).
 */
public record SlaCalendarUpdateRequest(String name, String timezone, Map<String, Object> workingHoursJson) {
}
