package com.githubci.intelligence.dto;

import java.util.List;

public record FailureAnalysis(
        FailureCategory category,
        String errorMessage,
        String suspectedCause,
        String suggestedInvestigation,
        ConfidenceLevel confidence,
        List<LogEvidence> evidence,
        List<LogEvidence> logContext,
        String normalizedSignature,
        String fingerprint,
        String analysisSource,
        String modelIdentifier,
        List<DiagnosticFinding> diagnosticFindings,
        String fallbackReason) {

    public FailureAnalysis(FailureCategory category, String errorMessage, String suspectedCause,
            String suggestedInvestigation, ConfidenceLevel confidence, List<LogEvidence> evidence,
            List<LogEvidence> logContext, String normalizedSignature, String fingerprint) {
        this(category, errorMessage, suspectedCause, suggestedInvestigation, confidence, evidence,
                logContext, normalizedSignature, fingerprint, "JAVA", null,
                List.of(new DiagnosticFinding(DiagnosticFinding.Kind.OBSERVATION, errorMessage, confidence, evidence),
                        new DiagnosticFinding(DiagnosticFinding.Kind.HYPOTHESIS, suspectedCause,
                                ConfidenceLevel.LOW, evidence)), null);
    }

    public FailureAnalysis {
        evidence = List.copyOf(evidence);
        logContext = List.copyOf(logContext);
        // Old persisted records predate provenance and diagnostics.
        analysisSource = analysisSource == null ? "LEGACY_JAVA" : analysisSource;
        diagnosticFindings = diagnosticFindings == null ? List.of() : List.copyOf(diagnosticFindings);
    }
}
