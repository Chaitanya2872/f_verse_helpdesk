package com.erp.ticketing.ticket.dto;

import java.util.Map;

/**
 * All fields are optional - a null field means "leave unchanged". Phase 1
 * does not support explicitly clearing a nullable field (e.g. unassigning by
 * sending assigneeId: null) since that would be indistinguishable from "not
 * provided" with this simple representation; a JsonNullable/patch-style
 * wrapper can be introduced in a later phase if that's needed.
 */
public record TicketUpdateRequest(
        Long categoryId,
        String title,
        String description,
        String priority,
        String status,
        String subStatus,
        Long assigneeId,
        Long departmentId,
        Map<String, Object> tags,
        Map<String, Object> customFields
) {
}
