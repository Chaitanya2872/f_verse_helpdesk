package com.erp.ticketing.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.Map;

/**
 * priority may be omitted when both impact and urgency are given - it is then
 * derived from the priority matrix (BRS 8.2). requesterId lets an authorised
 * agent raise a ticket on someone else's behalf (BRS 4.4); the creator is
 * always recorded separately.
 */
public record TicketCreateRequest(
        @NotNull Long typeId,
        Long categoryId,
        @NotBlank @Size(max = 500) String title,
        @Size(max = 20000) String description,
        String priority,
        Map<String, Object> tags,
        Map<String, Object> customFields,
        String confidentiality,
        String impact,
        String urgency,
        @Size(max = 100) String externalRef,
        Long requesterId
) {

    /** The original seven-field shape: standard confidentiality, no impact/urgency, own requester. */
    public TicketCreateRequest(Long typeId, Long categoryId, String title, String description, String priority,
                               Map<String, Object> tags, Map<String, Object> customFields) {
        this(typeId, categoryId, title, description, priority, tags, customFields, null, null, null, null, null);
    }
}
