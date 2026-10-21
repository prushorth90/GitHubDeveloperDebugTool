package com.githubci.intelligence.exceptions;

import org.springframework.http.HttpStatus;

public class FailureReadException extends RuntimeException {
    private final HttpStatus status;

    public FailureReadException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public FailureReadException(HttpStatus status, String message, Throwable cause) {
        super(message, cause);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
