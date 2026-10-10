package com.erp.ticketing.ticket.dto;

import jakarta.validation.constraints.NotBlank;

public record TicketTypeCreateRequest(@NotBlank String name, String description) {
}
