package com.truckhire.common.exception;

import com.truckhire.common.dto.ApiResponse;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * Global Exception Handler — catches ALL exceptions thrown by controllers.
 *
 * WHY THIS EXISTS:
 * Without this, Spring returns ugly HTML error pages or inconsistent JSON.
 * This class ensures EVERY error returns our standardized ApiResponse format.
 *
 * HOW IT WORKS:
 * - @RestControllerAdvice: Spring registers this as a global exception handler
 * - @ExceptionHandler(XxxException.class): Each method handles a specific
 * exception type
 * - Spring matches the thrown exception to the most specific handler
 *
 * FLOW:
 * Controller throws exception → Spring catches it → Matches handler → Returns
 * ApiResponse
 */
@Slf4j // Lombok: creates a logger (log.error(), log.warn(), etc.)
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * HTTP 404 — Resource not found
     * Example: GET /trucks/non-existent-id
     */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceNotFound(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.error("RESOURCE_NOT_FOUND", ex.getMessage()));
    }

    /**
     * HTTP 409 — Business rule violation
     * Example: Double booking, invalid state transition
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        log.warn("Business rule violation [{}]: {}", ex.getErrorCode(), ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.CONFLICT)
                .body(ApiResponse.error(ex.getErrorCode(), ex.getMessage()));
    }

    /**
     * HTTP 400 — Validation errors (from @Valid on request DTOs)
     * Example: Registration with empty email
     *
     * Returns a map of field → error message:
     * { "email": "must not be blank", "password": "size must be between 8 and 100"
     * }
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Map<String, String>>> handleValidationErrors(
            MethodArgumentNotValidException ex) {

        Map<String, String> errors = new HashMap<>();
        ex.getBindingResult().getAllErrors().forEach(error -> {
            String fieldName = ((FieldError) error).getField();
            String errorMessage = error.getDefaultMessage();
            errors.put(fieldName, errorMessage);
        });

        log.warn("Validation failed: {}", errors);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.<Map<String, String>>builder()
                        .success(false)
                        .error("VALIDATION_FAILED")
                        .message("Input validation failed")
                        .data(errors)
                        .build());
    }

    /**
     * HTTP 400 — Constraint violations (from @Validated on path/query params)
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException ex) {
        log.warn("Constraint violation: {}", ex.getMessage());
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("VALIDATION_FAILED", ex.getMessage()));
    }

    /**
     * HTTP 401 — Bad credentials (wrong password)
     */
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<ApiResponse<Void>> handleBadCredentials(BadCredentialsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNAUTHORIZED)
                .body(ApiResponse.error("INVALID_CREDENTIALS", "Invalid email or password"));
    }

    /**
     * HTTP 403 — Access denied (wrong role)
     * Example: A RENTER trying to add a truck (OWNER-only action)
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiResponse<Void>> handleAccessDenied(AccessDeniedException ex) {
        return ResponseEntity
                .status(HttpStatus.FORBIDDEN)
                .body(ApiResponse.error("ACCESS_DENIED", "You don't have permission to perform this action"));
    }

    /**
     * HTTP 400 — Type mismatch in path variables or query parameters.
     * Example: /admin/users/not-a-uuid/activate → UUID parse fails
     *
     * This catches cases where Spring cannot convert a URL parameter
     * to the expected type (e.g., String → UUID, String → Integer).
     */
    @ExceptionHandler(org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        Class<?> type = ex.getRequiredType();
        String requiredType = type != null ? type.getSimpleName() : "unknown";
        log.warn("Type mismatch: parameter '{}' could not be converted to {}", paramName, requiredType);
        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.error("INVALID_PARAMETER",
                        "Invalid value for parameter '" + paramName + "'. Expected type: " + requiredType));
    }

    /**
     * HTTP 500 — Catch-all for unexpected errors
     * Logs the full stack trace for debugging.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGenericException(Exception ex) {
        log.error("Unexpected error: ", ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.error("INTERNAL_ERROR", "An unexpected error occurred"));
    }
}
