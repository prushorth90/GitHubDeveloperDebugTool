package com.githubci.intelligence.models;

import java.time.Instant;

import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;

public record StoredWorkflowAnalysis(
        String repository,
        GitHubWorkflowRun workflowRun,
        AnalysisStatus status,
        WorkflowRunAnalysis analysis,
        Instant createdAt,
        Instant updatedAt,
        Instant leaseUntil) {
}
