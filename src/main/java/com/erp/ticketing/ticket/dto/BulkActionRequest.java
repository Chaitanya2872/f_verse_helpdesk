package com.erp.ticketing.ticket.dto;

import java.util.List;

/**
 * Request body for POST /api/helpdesk/tickets/bulk-action. {@code value} semantics
 * depend on {@code action}: ASSIGN_AGENT -> numeric assignee user id,
 * ASSIGN_DEPARTMENT -> numeric department id, CHANGE_STATUS -> status string,
 * CHANGE_PRIORITY -> priority string (P1-P4), CANCEL -> ignored. Typed as
 * Object (rather than String) so a plain JSON number can be sent for the
 * numeric actions without the caller having to stringify it - mirrors how
 * Ticket.tags/customFields already accept arbitrary JSON scalars.
 */
public record BulkActionRequest(
        List<Long> ticketIds,
        String action,
        Object value
) {
}
