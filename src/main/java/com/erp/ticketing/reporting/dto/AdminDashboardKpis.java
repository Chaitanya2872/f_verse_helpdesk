package com.erp.ticketing.reporting.dto;

public record AdminDashboardKpis(
        long totalOpenTickets,
        long totalSlaBreachesLast7Days,
        long totalUsers,
        long totalDepartments
) {
}
