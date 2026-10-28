package com.erp.ticketing.common;

/**
 * Thrown when a service this one depends on cannot be reached or answers with an error.
 * Handled by {@link GlobalExceptionHandler} and mapped to HTTP 503.
 */
public class ServiceUnavailableException extends RuntimeException {

    public ServiceUnavailableException(String message) {
        super(message);
    }
}
