package com.erp.ticketing.sla.dto;

import java.util.List;
import java.util.Map;

/**
 * All fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest). A non-null holidays list replaces the existing one.
 */
public record SlaCalendarUpdateRequest(String name, String timezone, Map<String, Object> workingHoursJson,
                                       List<String> holidays) {

    public SlaCalendarUpdateRequest(String name, String timezone, Map<String, Object> workingHoursJson) {
        this(name, timezone, workingHoursJson, null);
    }
}
