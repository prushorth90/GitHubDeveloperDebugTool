package com.githubci.intelligence.exceptions;

import org.springframework.http.HttpStatus;

public class LogStorageException extends RuntimeException {

    private final HttpStatus status;

    public LogStorageException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
