package com.erp.ticketing.ticket.dto;

/** One entry per requested ticket id in POST /api/helpdesk/tickets/bulk-action's response, in the same order as the request. */
public record BulkActionResult(
        Long ticketId,
        boolean success,
        String error
) {
}
