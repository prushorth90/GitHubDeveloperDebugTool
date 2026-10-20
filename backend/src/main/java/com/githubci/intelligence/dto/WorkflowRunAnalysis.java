package com.githubci.intelligence.dto;

import java.util.List;

public record WorkflowRunAnalysis(String repository, long runId, List<FailedJobAnalysis> failedJobs) {

    public WorkflowRunAnalysis {
        failedJobs = List.copyOf(failedJobs);
    }

    public record FailedJobAnalysis(
            GitHubJob job, LogStatus logStatus, String logMessage, FailureAnalysis analysis, String logObjectKey) {
    }

    public enum LogStatus {
        AVAILABLE,
        UNAVAILABLE
    }
}
