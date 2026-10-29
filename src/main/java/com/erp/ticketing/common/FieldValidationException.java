package com.erp.ticketing.common;

import java.util.Map;

/** Carries one message per offending form field so clients can show errors next to the inputs (BRS 5.4, AT-002). */
public class FieldValidationException extends RuntimeException {

    private final transient Map<String, String> errors;

    public FieldValidationException(Map<String, String> errors) {
        super("Form validation failed: " + errors);
        this.errors = errors;
    }

    public Map<String, String> getErrors() {
        return errors;
    }
}
