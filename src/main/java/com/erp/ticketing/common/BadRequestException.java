package com.erp.ticketing.common;

/**
 * Thrown for client-caused errors such as invalid status transitions.
 * Handled by {@link GlobalExceptionHandler} and mapped to HTTP 400.
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
