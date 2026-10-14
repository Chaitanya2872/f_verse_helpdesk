package com.erp.ticketing.department.dto;

public record DepartmentResponse(
        Long id,
        String departmentName,
        String code,
        String description,
        Long managerId,
        String managerName,
        Long slaPolicyId
) {
}
