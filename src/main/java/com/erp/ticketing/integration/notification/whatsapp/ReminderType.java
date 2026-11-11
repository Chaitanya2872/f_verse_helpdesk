package com.erp.ticketing.integration.notification.whatsapp;

/** The reminder kinds the {@code ticket_reminder} template's {@code {{7}}} reports, in escalating order. */
public enum ReminderType {
    FIRST_REMINDER("First Reminder"),
    SECOND_REMINDER("Second Reminder"),
    SLA_DUE_SOON("SLA Due Soon"),
    SLA_BREACHED("SLA Breached");

    private final String label;

    ReminderType(String label) {
        this.label = label;
    }

    /** The text shown in the message. */
    public String label() {
        return label;
    }
}
