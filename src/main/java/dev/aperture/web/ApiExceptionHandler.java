package dev.aperture.web;

import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns exceptions into JSON the UI can display.
 *
 * <p>Ordered {@link Ordered#HIGHEST_PRECEDENCE} so it outranks Spring's own
 * {@code ProblemDetailsExceptionHandler}. The catch-all re-throws anything that is already an
 * {@link ErrorResponse}, so framework exceptions keep the status codes they chose rather than all
 * collapsing into 500.
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(body(e.getMessage(), HttpStatus.BAD_REQUEST));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) throws Exception {
        if (e instanceof ErrorResponse) {
            throw e;
        }
        log.error("Unhandled error serving an API request", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(body(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                        HttpStatus.INTERNAL_SERVER_ERROR));
    }

    private static Map<String, Object> body(String message, HttpStatus status) {
        return Map.of(
                "error", message == null ? "Unexpected error" : message,
                "status", status.value(),
                "timestamp", Instant.now().toString());
    }
}
