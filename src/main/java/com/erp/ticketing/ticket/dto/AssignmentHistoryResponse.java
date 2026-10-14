package com.erp.ticketing.ticket.dto;

import java.time.Instant;

public record AssignmentHistoryResponse(
        Long id,
        String fromDepartment,
        String toDepartment,
        String fromAssignee,
        String toAssignee,
        String actor,
        String reason,
        Instant createdAt
) {
}
