package com.githubci.intelligence.services;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.repositories.WorkflowEventRepository;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowEventWorkerTest {

    private final WorkflowEvent event = new WorkflowEvent(1, "octo/demo", null, 42, 1,
            "failure", UUID.randomUUID().toString());
    private final WorkflowEventRepository events = mock(WorkflowEventRepository.class);
    private final WorkflowRunAnalysisService analysis = mock(WorkflowRunAnalysisService.class);
    private final WorkflowEventWorker worker = new WorkflowEventWorker(events, analysis);
    private final WorkflowEventRepository.Claim claim =
            new WorkflowEventRepository.Claim(event.idempotencyKey(), "token", false);

    @Test
    void completesOnlyAfterAllRequiredAnalysisWork() {
        when(events.claim(event)).thenReturn(claim);
        worker.process(event);
        var order = inOrder(events, analysis);
        order.verify(events).claim(event);
        order.verify(analysis).analyze(event);
        order.verify(events).complete(claim);
        verify(events, never()).fail(any());
    }

    @Test
    void completedDuplicatesNeedNoGitHubOrStorageWork() {
        when(events.claim(event)).thenReturn(new WorkflowEventRepository.Claim(claim.key(), null, true));
        worker.process(event);
        verifyNoInteractions(analysis);
        verify(events, never()).complete(any());
    }

    @Test
    void analysisFailureIsRetryableAndNotSuccessful() {
        when(events.claim(event)).thenReturn(claim);
        var failure = new IllegalStateException("S3 unavailable");
        when(analysis.analyze(event)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> worker.process(event)));
        verify(events).fail(claim);
        verify(events, never()).complete(any());
    }

    @Test
    void failureToPersistCompletionCannotAcknowledge() {
        when(events.claim(event)).thenReturn(claim);
        doThrow(new IllegalStateException("DynamoDB timeout")).when(events).complete(claim);
        assertThrows(IllegalStateException.class, () -> worker.process(event));
        verify(events).fail(claim);
    }

    @Test
    void heartbeatExtendsOwnershipAndVisibilityAndFailurePreventsCompletion() throws Exception {
        when(events.claim(event)).thenReturn(claim);
        var heartbeat = new CountDownLatch(1);
        when(analysis.analyze(event)).thenAnswer(invocation -> {
            assertTrue(heartbeat.await(10, TimeUnit.SECONDS));
            return null;
        });
        assertThrows(IllegalStateException.class, () -> worker.process(event, () -> {
            heartbeat.countDown();
            throw new IllegalStateException("Visibility extension failed");
        }));
        verify(events).renew(claim);
        verify(events, never()).complete(any());
        verify(events).fail(claim);
    }

    @Test
    void stableIdentityExcludesDeliveryAndRepositoryNameButIncludesAttempt() {
        var renamed = new WorkflowEvent(1, "octo/renamed", 99L, 42, 1,
                "failure", UUID.randomUUID().toString());
        assertEquals(event.idempotencyKey(), renamed.idempotencyKey());
        assertNotEquals(event.idempotencyKey(), new WorkflowEvent(1, "octo/demo", null, 42, 2,
                "failure", event.deliveryId()).idempotencyKey());
        assertThrows(IllegalArgumentException.class, () -> worker.process(
                new WorkflowEvent(0, "octo/demo", null, 42, 1, "failure", event.deliveryId())));
        verifyNoInteractions(events, analysis);
    }
}
