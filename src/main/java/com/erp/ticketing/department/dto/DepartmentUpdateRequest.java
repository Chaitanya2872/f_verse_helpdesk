package com.erp.ticketing.department.dto;

/**
 * All fields are optional - a null field means "leave unchanged", matching the
 * same partial-update convention used by {@code TicketUpdateRequest}. There is
 * no support for explicitly clearing {@code description}/{@code managerId}/
 * {@code slaPolicyId} back to null with this simple representation (see
 * TicketUpdateRequest's javadoc for the same tradeoff).
 */
public record DepartmentUpdateRequest(
        String departmentName,
        String code,
        String description,
        Long managerId,
        Long slaPolicyId
) {
}
