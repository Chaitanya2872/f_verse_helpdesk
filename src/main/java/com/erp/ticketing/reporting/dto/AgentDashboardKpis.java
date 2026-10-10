package com.erp.ticketing.reporting.dto;

public record AgentDashboardKpis(
        long myOpenTickets,
        long myOverdueTickets,
        long slaBreachRiskNext4h,
        Double avgFirstResponseMinutesMine
) {
}
