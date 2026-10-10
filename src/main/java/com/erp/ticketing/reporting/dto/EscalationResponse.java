package com.erp.ticketing.reporting.dto;

public record EscalationResponse(
        Long ticketId,
        String ticketNumber,
        String title,
        Long departmentId,
        String departmentName,
        Long assigneeId,
        String assigneeName,
        String breachStatus,
        String breachType,
        Long minutesOverdue,
        Long minutesUntilDue
) {
}
