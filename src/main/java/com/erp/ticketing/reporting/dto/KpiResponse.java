package com.erp.ticketing.reporting.dto;

import java.util.Map;

/**
 * Standardised KPIs (BRS 17.2) over the tickets the caller may see. Minutes/hours are wall-clock
 * time; {@code definitions} states exactly how each number is computed so reports stay comparable.
 */
public record KpiResponse(
        long ticketsInPeriod,
        Double firstResponseAvgMinutes,
        Double resolutionAvgMinutes,
        Double slaAchievementPercent,
        long slaEligibleTickets,
        long backlog,
        Double avgOpenAgeHours,
        Double reopenRatePercent,
        Double approvalTurnaroundAvgHours,
        Map<String, String> definitions
) {
}
