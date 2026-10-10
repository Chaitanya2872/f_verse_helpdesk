package com.erp.ticketing.reporting.dto;

public record RequesterDashboardKpis(
        long myOpenTickets,
        long myAwaitingResponse,
        long myResolvedLast30Days,
        Double avgResolutionMinutesMine
) {
}
