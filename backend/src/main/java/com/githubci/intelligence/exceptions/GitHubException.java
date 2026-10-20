package com.githubci.intelligence.exceptions;

import org.springframework.http.HttpStatus;

public class GitHubException extends RuntimeException {

    private final HttpStatus status;
    private final String retryAfter;

    public GitHubException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public GitHubException(HttpStatus status, String message, String retryAfter) {
        super(message);
        this.status = status;
        this.retryAfter = retryAfter;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getRetryAfter() {
        return retryAfter;
    }
}
