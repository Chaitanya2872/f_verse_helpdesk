package com.erp.ticketing.routing.dto;

public record RoutingRuleResponse(
        Long id,
        String name,
        int orderIndex,
        Long ticketTypeId,
        Long categoryId,
        String priority,
        Long departmentId,
        Long assigneeId,
        boolean isActive
) {
}
