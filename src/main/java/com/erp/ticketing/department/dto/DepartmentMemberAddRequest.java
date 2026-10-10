package com.erp.ticketing.department.dto;

import jakarta.validation.constraints.NotNull;

public record DepartmentMemberAddRequest(@NotNull Long userId, boolean isLead) {
}
