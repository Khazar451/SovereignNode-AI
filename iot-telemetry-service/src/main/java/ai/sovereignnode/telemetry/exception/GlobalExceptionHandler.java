package ai.sovereignnode.telemetry.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * <h2>GlobalExceptionHandler – Centralised RFC 9457 Error Responses</h2>
 *
 * <p>Converts exceptions into RFC 9457 Problem Detail responses using Spring 6's
 * built-in {@link ProblemDetail} support. All errors are structured as:
 * <pre>{@code
 * {
 *   "type":       "https://sovereignnode.ai/errors/validation-failed",
 *   "title":      "Validation Failed",
 *   "status":     400,
 *   "detail":     "Request body contains invalid fields",
 *   "instance":   "/api/v1/telemetry",
 *   "timestamp":  "2026-04-23T17:00:00Z",
 *   "violations": { "sensorId": "sensor_id is required", ... }
 * }
 * }</pre>
 *
 * <p>This guarantees a consistent, machine-parseable error contract for all
 * API consumers (edge devices, AI engines, dashboards).
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    private static final String ERROR_TYPE_BASE = "https://sovereignnode.ai/errors/";

    // ─── Bean Validation Failures ─────────────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidationException(MethodArgumentNotValidException ex,
                                                   WebRequest request) {
        Map<String, String> violations = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        fe -> fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid",
                        (a, b) -> a   // keep first violation per field
                ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Request body contains one or more invalid fields");

        problem.setType(URI.create(ERROR_TYPE_BASE + "validation-failed"));
        problem.setTitle("Validation Failed");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("violations", violations);

        log.warn("Validation failure for request '{}': {}",
                 request.getDescription(false), violations);
        return problem;
    }

    // ─── Constraint Violation (path/query params) ─────────────────────────

    @ExceptionHandler(jakarta.validation.ConstraintViolationException.class)
    public ProblemDetail handleConstraintViolation(
            jakarta.validation.ConstraintViolationException ex, WebRequest request) {

        Map<String, String> violations = ex.getConstraintViolations().stream()
                .collect(Collectors.toMap(
                        cv -> cv.getPropertyPath().toString(),
                        cv -> cv.getMessage(),
                        (a, b) -> a
                ));

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "One or more request parameters are invalid");
        problem.setType(URI.create(ERROR_TYPE_BASE + "constraint-violation"));
        problem.setTitle("Constraint Violation");
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("violations", violations);

        log.warn("Constraint violation: {}", violations);
        return problem;
    }

    // ─── Generic Fallback ─────────────────────────────────────────────────

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleGenericException(Exception ex, WebRequest request) {
        log.error("Unhandled exception on request '{}': {}",
                  request.getDescription(false), ex.getMessage(), ex);

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please refer to service logs.");
        problem.setType(URI.create(ERROR_TYPE_BASE + "internal-error"));
        problem.setTitle("Internal Server Error");
        problem.setProperty("timestamp", Instant.now());
        return problem;
    }
}
