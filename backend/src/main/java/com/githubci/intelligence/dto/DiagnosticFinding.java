package com.githubci.intelligence.dto;

import java.util.List;

public record DiagnosticFinding(Kind kind, String description, ConfidenceLevel confidence,
                                List<LogEvidence> evidence) {
    public enum Kind { OBSERVATION, HYPOTHESIS }

    public DiagnosticFinding {
        evidence = List.copyOf(evidence);
    }
}
