package com.truckhire.common.exception;

/**
 * Thrown when a business rule is violated.
 * Example: Trying to book a truck that's already booked for those dates.
 *
 * The GlobalExceptionHandler catches this and returns HTTP 409 (Conflict).
 */
public class BusinessException extends RuntimeException {

    private final String errorCode;

    public BusinessException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
