package com.erp.ticketing.automation.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AutomationDto {

    private AutomationDto() {
    }

    public record Rule(Long id, String name, String ownerName, boolean enabled, String trigger,
                       Map<String, Object> conditions, List<Map<String, Object>> actions, int maxRunsPerTicket,
                       int version, Instant updatedAt) {
    }

    public record SaveRequest(String name, String trigger, Boolean enabled, Map<String, Object> conditions,
                              List<Map<String, Object>> actions, Integer maxRunsPerTicket) {
    }

    public record Run(Long id, int ruleVersion, Long ticketId, boolean dryRun, String result,
                      List<Map<String, Object>> actions, String error, Instant createdAt) {
    }

    public record DryRunResult(boolean matches, List<Map<String, Object>> wouldApply) {
    }
}
