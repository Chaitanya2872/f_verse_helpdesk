package com.erp.ticketing.sla.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SlaCalendarCreateRequest(@NotBlank String name, String timezone, Map<String, Object> workingHoursJson) {
}
