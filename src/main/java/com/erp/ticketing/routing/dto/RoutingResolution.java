package com.erp.ticketing.routing.dto;

/**
 * The outcome of a matched routing rule. Either field may be null even on a
 * match - e.g. a rule may route to a department without pre-assigning an agent.
 */
public record RoutingResolution(Long departmentId, Long assigneeId) {
}
