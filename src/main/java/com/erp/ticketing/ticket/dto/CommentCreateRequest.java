package com.erp.ticketing.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record CommentCreateRequest(@NotBlank String body, boolean isInternal) {
}
