package com.erp.ticketing.ticket.dto;

import java.util.Map;

/**
 * All fields are optional - a null field means "leave unchanged". Explicitly
 * clearing a nullable field is not supported. {@code version} is the ticket
 * version the client last saw; when sent and stale the update is rejected with
 * 409 instead of silently overwriting a concurrent change (BRS 20.1, AT-018).
 * {@code reason} is stored on assignment-history rows.
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
        Map<String, Object> customFields,
        Long version,
        String resolutionCode,
        String resolutionSummary,
        String reason,
        String confidentiality,
        String impact,
        String urgency
) {

    /** The original ten-field shape, for callers that only touch the basic fields. */
    public TicketUpdateRequest(Long categoryId, String title, String description, String priority, String status,
                               String subStatus, Long assigneeId, Long departmentId,
                               Map<String, Object> tags, Map<String, Object> customFields) {
        this(categoryId, title, description, priority, status, subStatus, assigneeId, departmentId,
                tags, customFields, null, null, null, null, null, null, null);
    }
}
