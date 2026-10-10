package com.erp.ticketing.routing.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Shared shape for create and update. As with {@code TicketUpdateRequest}, a
 * null field on update means "leave unchanged" - there is no way to explicitly
 * clear a wildcard field (ticketTypeId/categoryId/priority/departmentId/assigneeId)
 * back to null via this simple representation once set. On create, name is
 * required; everything else defaults to null/wildcard (isActive defaults to
 * true) when omitted.
 */
public record RoutingRuleRequest(
        @NotBlank String name,
        Integer orderIndex,
        Long ticketTypeId,
        Long categoryId,
        String priority,
        Long departmentId,
        Long assigneeId,
        Boolean isActive
) {
}
