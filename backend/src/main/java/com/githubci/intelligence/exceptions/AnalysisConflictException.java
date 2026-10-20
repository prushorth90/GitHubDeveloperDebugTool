package com.githubci.intelligence.exceptions;

public class AnalysisConflictException extends RuntimeException {

    public AnalysisConflictException() {
        super("Analysis is already processing or its lease was lost; retry later");
    }
}
