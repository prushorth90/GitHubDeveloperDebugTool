package com.githubci.intelligence.services;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.githubci.intelligence.dto.GitHubRepository;
import com.githubci.intelligence.dto.GitHubWorkflowRun;
import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.dto.WorkflowRunAnalysis;
import com.githubci.intelligence.models.AnalysisStatus;
import com.githubci.intelligence.models.StoredWorkflowAnalysis;
import com.githubci.intelligence.repositories.WorkflowAnalysisRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowMetadataTest {
    @ParameterizedTest
    @ValueSource(strings = {"success", "cancelled", "skipped", "neutral", "action_required", "stale"})
    void retainsNonFailureMetadataWithoutFetchingJobsOrLogsAndReusesCompletedClaims(String conclusion) {
        var github = mock(GitHubService.class);
        var repository = mock(WorkflowAnalysisRepository.class);
        var analyzer = mock(FailureAnalyzerService.class);
        var logs = mock(LogStorageService.class);
        var redactor = mock(LogRedactionService.class);
        var service = new WorkflowRunAnalysisService(github, analyzer, repository, logs, redactor);
        var now = Instant.parse("2026-10-20T12:00:00Z");
        var run = new GitHubWorkflowRun(42, "CI", 1, 1, 2, "push", "completed", conclusion,
                "main", "sha", null, now, now, new GitHubRepository(1, "demo", "octo/demo", null, false));
        var event = new WorkflowEvent(1, "octo/demo", null, 42, 2, conclusion, UUID.randomUUID().toString());
        when(github.getWorkflowRunAttempt("octo", "demo", 42, 2)).thenReturn(run);
        var claim = new WorkflowAnalysisRepository.Claim(new StoredWorkflowAnalysis(
                "octo/demo", run, AnalysisStatus.PROCESSING, null, now, now, now.plusSeconds(300)), "token");
        when(repository.claim("octo/demo", run)).thenReturn(claim);
        var expected = new WorkflowRunAnalysis("octo/demo", 42, List.of());
        assertEquals(expected, service.analyze(event));
        verify(repository).complete(claim, expected);
        verifyNoInteractions(analyzer, logs, redactor);
        verify(github, never()).listJobsForAttempt(anyString(), anyString(), anyLong(), anyInt(), anyInt(), anyInt());
        when(repository.claim("octo/demo", run)).thenReturn(new WorkflowAnalysisRepository.Claim(
                new StoredWorkflowAnalysis("octo/demo", run, AnalysisStatus.COMPLETED, expected, now, now, null), null));
        assertEquals(expected, service.analyze(event));
        verify(repository, times(1)).complete(any(), any());
    }
}
