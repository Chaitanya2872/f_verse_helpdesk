package com.erp.ticketing.common;

/**
 * Thrown when a requested resource does not exist (or is not visible to the caller).
 * Handled by {@link GlobalExceptionHandler} and mapped to HTTP 404.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
