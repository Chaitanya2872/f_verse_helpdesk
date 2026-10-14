package com.erp.ticketing.ticket.model;

/**
 * Example impact x urgency matrix from BRS 8.2. Cells the spec leaves open
 * ("P1 or P2", "P2 or P3") resolve to the lower severity; service owners
 * approve the final matrix, so this is the single place to change it.
 */
public final class PriorityMatrix {

    private PriorityMatrix() {
    }

    public static TicketPriority resolve(Level impact, Level urgency) {
        return switch (impact) {
            case HIGH -> urgency == Level.HIGH ? TicketPriority.P1 : TicketPriority.P2;
            case MEDIUM -> urgency == Level.LOW ? TicketPriority.P3 : TicketPriority.P2;
            case LOW -> urgency == Level.LOW ? TicketPriority.P4 : TicketPriority.P3;
        };
    }
}
