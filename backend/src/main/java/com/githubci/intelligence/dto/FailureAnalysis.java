package com.githubci.intelligence.dto;

import java.util.List;

public record FailureAnalysis(
        FailureCategory category,
        String errorMessage,
        String suspectedCause,
        String suggestedInvestigation,
        ConfidenceLevel confidence,
        List<LogEvidence> evidence,
        List<LogEvidence> logContext) {

    public FailureAnalysis {
        evidence = List.copyOf(evidence);
        logContext = List.copyOf(logContext);
    }
}
