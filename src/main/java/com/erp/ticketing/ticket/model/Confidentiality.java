package com.erp.ticketing.ticket.model;

/** STANDARD tickets follow normal department visibility; the others need an explicit grant. */
public enum Confidentiality {
    STANDARD, RESTRICTED, CONFIDENTIAL
}
