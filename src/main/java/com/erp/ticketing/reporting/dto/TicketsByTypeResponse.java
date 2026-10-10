package com.erp.ticketing.reporting.dto;

public record TicketsByTypeResponse(Long typeId, String typeName, long count) {
}
