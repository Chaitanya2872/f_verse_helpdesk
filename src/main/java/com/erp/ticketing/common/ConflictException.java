package com.erp.ticketing.common;

/** Maps to HTTP 409: the request is valid but conflicts with the current state (e.g. a stale version). */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
