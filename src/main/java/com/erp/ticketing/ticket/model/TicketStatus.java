package com.erp.ticketing.ticket.model;

/**
 * Baseline lifecycle (BRS 6.3). NEW is the "Submitted" state; WAITING_CUSTOMER
 * is "Waiting for Requester" and WAITING_VENDOR is "Waiting for Third Party".
 */
public enum TicketStatus {
    NEW, TRIAGE, ASSIGNED, IN_PROGRESS, WAITING_CUSTOMER, WAITING_APPROVAL, WAITING_VENDOR,
    RESOLVED, CLOSED, CANCELLED, REOPENED
}
