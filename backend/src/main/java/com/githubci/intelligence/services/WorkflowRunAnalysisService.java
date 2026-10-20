package com.githubci.intelligence.services;

import java.util.ArrayList;
import java.util.List;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowRunAnalysis.FailedJobAnalysis;
import com.githubci.intelligence.exceptions.GitHubException;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import static com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus.AVAILABLE;
import static com.githubci.intelligence.dto.WorkflowRunAnalysis.LogStatus.UNAVAILABLE;

@Service
public class WorkflowRunAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(WorkflowRunAnalysisService.class);
    private static final int PAGE_SIZE = 100;
    private final GitHubService github;
    private final FailureAnalyzerService analyzer;
    private final WorkflowAnalysisRepository repository;

    public WorkflowRunAnalysisService(
            GitHubService github, FailureAnalyzerService analyzer, WorkflowAnalysisRepository repository) {
        this.github = github;
        this.analyzer = analyzer;
        this.repository = repository;
    }

    public WorkflowRunAnalysis analyze(String owner, String repo, long runId) {
        GitHubWorkflowRun run = github.getWorkflowRun(owner, repo, runId);
        if (run.id() != runId || run.runAttempt() == null || run.runAttempt() <= 0) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned invalid workflow run metadata");
        }
        if (!"completed".equals(run.status())) {
            throw new GitHubException(HttpStatus.CONFLICT, "Workflow run must be completed before analysis");
        }
        var claim = repository.claim(owner + "/" + repo, run);
        if (claim.completed()) {
            return claim.record().analysis();
        }
        try {
            WorkflowRunAnalysis result = analyzeAttempt(owner, repo, runId, run.runAttempt());
            repository.complete(claim, result);
            return result;
        } catch (RuntimeException failure) {
            // Persist a retryable state without hiding the original GitHub/storage failure.
            try {
                if (!repository.fail(claim)) {
                    log.warn("Analysis failure state not written because processing ownership was lost");
                }
            } catch (RuntimeException persistenceFailure) {
                log.error("Failed to persist retryable workflow analysis state", persistenceFailure);
                failure.addSuppressed(persistenceFailure);
            }
            throw failure;
        }
    }

    private WorkflowRunAnalysis analyzeAttempt(String owner, String repo, long runId, int attempt) {
        List<FailedJobAnalysis> failures = new ArrayList<>();
        long retrieved = 0;
        for (int page = 1; ; page++) {
            GitHubJobs jobs = github.listJobsForAttempt(owner, repo, runId, attempt, page, PAGE_SIZE);
            for (GitHubJob job : jobs.jobs()) {
                if (job == null || job.id() <= 0 || job.runId() != runId) {
                    throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid job");
                }
                if ("failure".equals(job.conclusion()) || "timed_out".equals(job.conclusion())) {
                    failures.add(analyzeJob(owner, repo, job));
                }
            }
            retrieved += jobs.jobs().size();
            if (retrieved >= jobs.totalCount()) {
                break;
            }
            if (jobs.jobs().isEmpty()) {
                throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an incomplete jobs response");
            }
        }
        return new WorkflowRunAnalysis(owner + "/" + repo, runId, failures);
    }

    private FailedJobAnalysis analyzeJob(String owner, String repo, GitHubJob job) {
        String logs;
        try {
            logs = github.getFailedJobLogs(owner, repo, job.id());
        } catch (GitHubException exception) {
            if (exception.getStatus() != HttpStatus.GONE) {
                throw exception;
            }
            return new FailedJobAnalysis(job, UNAVAILABLE, exception.getMessage(), null);
        }
        if (logs.isBlank()) {
            return new FailedJobAnalysis(job, UNAVAILABLE, "GitHub job logs are empty", null);
        }
        return new FailedJobAnalysis(job, AVAILABLE, null, analyzer.analyze(logs));
    }
}
