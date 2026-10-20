package com.githubci.intelligence.exceptions;

import java.time.Clock;
import java.time.Instant;

import com.githubci.intelligence.dto.ApiError;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final Clock clock;

    public GlobalExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException exception, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return error(status, headers, "Request validation failed", request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (status.is5xxServerError()) {
            log.error("Request processing failed", exception);
        }
        return error(status, headers, reason(status), request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(
            ConstraintViolationException exception, WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, HttpHeaders.EMPTY, "Request validation failed", request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpectedException(Exception exception, WebRequest request) {
        log.error("Unexpected request failure", exception);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY,
                "An unexpected error occurred", request);
    }

    @ExceptionHandler(GitHubException.class)
    public ResponseEntity<Object> handleGitHubException(GitHubException exception, WebRequest request) {
        log.warn("GitHub request failed: status={}, message={}", exception.getStatus().value(), exception.getMessage());
        HttpHeaders headers = new HttpHeaders();
        if (exception.getRetryAfter() != null) {
            headers.set(HttpHeaders.RETRY_AFTER, exception.getRetryAfter());
        }
        return error(exception.getStatus(), headers, exception.getMessage(), request);
    }

    @ExceptionHandler(WebhookException.class)
    public ResponseEntity<Object> handleWebhookException(WebhookException exception, WebRequest request) {
        log.warn("GitHub webhook rejected: status={}, message={}",
                exception.getStatus().value(), exception.getMessage());
        return error(exception.getStatus(), HttpHeaders.EMPTY, exception.getMessage(), request);
    }

    @ExceptionHandler(AnalysisConflictException.class)
    public ResponseEntity<Object> handleAnalysisConflict(AnalysisConflictException exception, WebRequest request) {
        log.warn("Workflow analysis conflict: {}", exception.getMessage());
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.RETRY_AFTER, "30");
        return error(HttpStatus.CONFLICT, headers, exception.getMessage(), request);
    }

    @ExceptionHandler(LogStorageException.class)
    public ResponseEntity<Object> handleLogStorageException(LogStorageException exception, WebRequest request) {
        log.warn("Job log storage failed: status={}, message={}",
                exception.getStatus().value(), exception.getMessage());
        return error(exception.getStatus(), HttpHeaders.EMPTY, exception.getMessage(), request);
    }

    private ResponseEntity<Object> error(
            HttpStatusCode status, HttpHeaders headers, String message, WebRequest request) {
        String path = ((ServletWebRequest) request).getRequest().getRequestURI();
        ApiError body = new ApiError(Instant.now(clock), status.value(), reason(status), message, path);
        return new ResponseEntity<>(body, headers, status);
    }

    private String reason(HttpStatusCode status) {
        HttpStatus knownStatus = HttpStatus.resolve(status.value());
        return knownStatus == null ? "HTTP " + status.value() : knownStatus.getReasonPhrase();
    }
}
