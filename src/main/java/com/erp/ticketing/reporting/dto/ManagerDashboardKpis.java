package com.erp.ticketing.reporting.dto;

public record ManagerDashboardKpis(
        long departmentOpenTickets,
        long departmentOverdueTickets,
        long slaBreachCountLast7Days,
        Double avgResolutionMinutesDepartment
) {
}
