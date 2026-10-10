package com.erp.ticketing.ticket.dto;

/** Response body for GET /api/helpdesk/tickets/summary - role-aware header counts for the Tickets list page. */
public record TicketSummaryResponse(
        long totalTickets,
        long openTickets,
        long slaAtRiskTickets,
        long unassignedTickets,
        long inProgressTickets,
        long onHoldTickets,
        long resolvedTickets,
        Double totalTicketsTrendPercent,
        Double resolvedTicketsTrendPercent
) {
}
