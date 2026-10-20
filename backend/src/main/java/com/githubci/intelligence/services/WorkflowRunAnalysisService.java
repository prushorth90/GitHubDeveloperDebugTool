package com.githubci.intelligence.services;

import java.util.ArrayList;
import java.util.List;

import com.githubci.intelligence.dto.GitHubJob;
import com.githubci.intelligence.dto.GitHubJobs;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.dto.WorkflowEvent;
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
    private final LogStorageService logStorage;
    private final LogRedactionService redactor;

    public WorkflowRunAnalysisService(
            GitHubService github, FailureAnalyzerService analyzer, WorkflowAnalysisRepository repository,
            LogStorageService logStorage, LogRedactionService redactor) {
        this.github = github;
        this.analyzer = analyzer;
        this.repository = repository;
        this.logStorage = logStorage;
        this.redactor = redactor;
    }

    public WorkflowRunAnalysis analyze(String owner, String repo, long runId) {
        GitHubWorkflowRun run = github.getWorkflowRun(owner, repo, runId);
        return analyze(owner, repo, runId, run);
    }

    public WorkflowRunAnalysis analyze(WorkflowEvent event) {
        event.validate();
        String[] name = event.repositoryName().split("/");
        GitHubWorkflowRun run = github.getWorkflowRunAttempt(
                name[0], name[1], event.workflowRunId(), event.runAttempt());
        if (run.runAttempt() == null || run.runAttempt() != event.runAttempt()
                || run.repository() == null || run.repository().id() != event.repositoryId()
                || !event.conclusion().equals(run.conclusion())) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub run does not match workflow event");
        }
        return analyze(name[0], name[1], event.workflowRunId(), run);
    }

    private WorkflowRunAnalysis analyze(String owner, String repo, long runId, GitHubWorkflowRun run) {
        if (run.id() != runId || run.runAttempt() == null || run.runAttempt() <= 0) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned invalid workflow run metadata");
        }
        if (!"completed".equals(run.status())) {
            throw new GitHubException(HttpStatus.CONFLICT, "Workflow run must be completed before analysis");
        }
        if (run.repository() == null || run.repository().id() <= 0) {
            throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned invalid repository metadata");
        }
        var claim = repository.claim(owner + "/" + repo, run);
        if (claim.completed()) {
            return claim.record().analysis();
        }
        try {
            WorkflowRunAnalysis result = analyzeAttempt(owner, repo, run, claim);
            repository.renew(claim);
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

    private WorkflowRunAnalysis analyzeAttempt(String owner, String repo, GitHubWorkflowRun run,
            WorkflowAnalysisRepository.Claim claim) {
        long runId = run.id();
        int attempt = run.runAttempt();
        List<FailedJobAnalysis> failures = new ArrayList<>();
        long retrieved = 0;
        for (int page = 1; ; page++) {
            repository.renew(claim);
            GitHubJobs jobs = github.listJobsForAttempt(owner, repo, runId, attempt, page, PAGE_SIZE);
            for (GitHubJob job : jobs.jobs()) {
                if (job == null || job.id() <= 0 || job.runId() != runId) {
                    throw new GitHubException(HttpStatus.BAD_GATEWAY, "GitHub returned an invalid job");
                }
                if ("failure".equals(job.conclusion()) || "timed_out".equals(job.conclusion())) {
                    repository.renew(claim);
                    failures.add(analyzeJob(owner, repo, run, job));
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

    private FailedJobAnalysis analyzeJob(String owner, String repo, GitHubWorkflowRun run, GitHubJob job) {
        String logs;
        try {
            logs = github.getFailedJobLogs(owner, repo, job.id());
        } catch (GitHubException exception) {
            if (exception.getStatus() != HttpStatus.GONE) {
                throw exception;
            }
            return new FailedJobAnalysis(job, UNAVAILABLE, exception.getMessage(), null, null);
        }
        if (logs.isBlank()) {
            return new FailedJobAnalysis(job, UNAVAILABLE, "GitHub job logs are empty", null, null);
        }
        String sanitized = redactor.redact(logs);
        String objectKey = logStorage.upload(run.repository().id(), run.id(), run.runAttempt(), job.id(), sanitized);
        return new FailedJobAnalysis(job, AVAILABLE, null, analyzer.analyze(sanitized), objectKey);
    }
}
