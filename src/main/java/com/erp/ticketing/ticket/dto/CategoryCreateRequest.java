package com.erp.ticketing.ticket.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryCreateRequest(@NotNull Long ticketTypeId, @NotBlank String name, Long parentCategoryId) {
}
