package com.erp.ticketing.ticket.dto;

/**
 * Additional list filters beyond the basic queue ones (BRS 4.2 advanced search). Every field is
 * optional; blank means "not filtered".
 */
public record TicketFilterExtras(
        String externalRef,
        String confidentiality,
        String resolutionCode,
        Boolean reopened,
        String source
) {

    public static final TicketFilterExtras NONE = new TicketFilterExtras(null, null, null, null, null);
}
