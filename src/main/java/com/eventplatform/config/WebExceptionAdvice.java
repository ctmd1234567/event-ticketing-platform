package com.eventplatform.config;

import com.eventplatform.dto.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class WebExceptionAdvice {
    private static final Logger log = LoggerFactory.getLogger(WebExceptionAdvice.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Result> status(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Result.fail(exception.getReason()));
    }

    @ExceptionHandler({IllegalArgumentException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class})
    public ResponseEntity<Result> invalid(Exception exception) {
        return ResponseEntity.badRequest().body(Result.fail("Invalid request parameters"));
    }

    @ExceptionHandler({CannotGetJdbcConnectionException.class, CannotCreateTransactionException.class})
    public ResponseEntity<Result> databaseUnavailable(RuntimeException exception) {
        log.warn("Event database is temporarily unavailable: {}", exception.toString());
        return ResponseEntity.status(503).body(Result.fail("Database is temporarily unavailable"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Result> unexpected(RuntimeException exception) {
        log.error("Request failed", exception);
        return ResponseEntity.internalServerError().body(Result.fail("Internal server error"));
    }
}
