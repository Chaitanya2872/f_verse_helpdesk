package com.erp.ticketing.reporting.dto;

public record DepartmentWorkloadResponse(Long assigneeId, String assigneeName, long openTicketCount) {
}
