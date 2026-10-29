package com.erp.ticketing.department.dto;

import jakarta.validation.constraints.NotBlank;

public record DepartmentCreateRequest(
        @NotBlank String departmentName,
        String code,
        String description,
        Long managerId,
        Long slaPolicyId
) {
}
