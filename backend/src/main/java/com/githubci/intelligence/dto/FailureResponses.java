package com.githubci.intelligence.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.githubci.intelligence.models.AnalysisStatus;

public final class FailureResponses {
    private FailureResponses() { }

    public record ApiResponse<T>(T data) { }
    public record Page<T>(List<T> items, String nextToken) {
        public Page { items = List.copyOf(items); }
    }
    public record Repository(long id, String name, String fullName, String htmlUrl, boolean privateRepository) { }
    public record Run(long runId, int attempt, String workflowName, String branch, String conclusion,
                      String htmlUrl, Instant createdAt, Instant analyzedAt, AnalysisStatus analysisStatus,
                      boolean analysisComplete, List<FailureCategory> categories) {
        public Run { categories = List.copyOf(categories); }
    }
    public record JobFailure(long jobId, String name, WorkflowRunAnalysis.LogStatus logStatus,
                             String logMessage, FailureAnalysis analysis) { }
    public record Failure(Run run, List<JobFailure> failedJobs) {
        public Failure { failedJobs = List.copyOf(failedJobs); }
    }
    public record Statistics(long totalRuns, long completedAnalyses, long processingAnalyses,
                             long failedAnalyses, long failureRuns, long unavailableJobAnalyses,
                             Map<FailureCategory, Long> failuresByCategory, Run latestRun, Instant lastAnalyzedAt) {
        public Statistics { failuresByCategory = Map.copyOf(failuresByCategory); }
    }
}
