package com.erp.ticketing.ticket.dto;

/**
 * Both fields optional - null means "leave unchanged" (same convention as
 * TeamUpdateRequest).
 */
public record TicketTypeUpdateRequest(String name, String description) {
}
