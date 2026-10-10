package com.erp.ticketing.reporting.dto;

/** One entry per UTC calendar day for GET /api/helpdesk/reports/ticket-volume-trend. */
public record TicketVolumeTrendResponse(String date, long created, long resolved) {
}
