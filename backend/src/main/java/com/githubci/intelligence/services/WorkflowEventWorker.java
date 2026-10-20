package com.githubci.intelligence.services;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.repositories.WorkflowEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class WorkflowEventWorker {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEventWorker.class);
    private final WorkflowEventRepository events;
    private final WorkflowRunAnalysisService analysis;

    public WorkflowEventWorker(WorkflowEventRepository events, WorkflowRunAnalysisService analysis) {
        this.events = events;
        this.analysis = analysis;
    }

    public void process(WorkflowEvent event) {
        process(event, () -> { });
    }

    // The callback extends a transport lease; local callers need no queue or Lambda types.
    public void process(WorkflowEvent event, Runnable extendVisibility) {
        event.validate();
        var claim = events.claim(event);
        if (claim.completed()) {
            return;
        }
        Object lock = new Object();
        var active = new AtomicBoolean(true);
        var heartbeatFailure = new AtomicReference<RuntimeException>();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var heartbeat = scheduler.scheduleAtFixedRate(() -> {
            synchronized (lock) {
                if (!active.get() || heartbeatFailure.get() != null) {
                    return;
                }
                try {
                    events.renew(claim);
                    extendVisibility.run();
                } catch (RuntimeException failure) {
                    heartbeatFailure.set(failure);
                    log.warn("Workflow heartbeat failed: key={}, failureType={}",
                            claim.key(), failure.getClass().getSimpleName());
                }
            }
        }, 5, 5, TimeUnit.SECONDS);
        try {
            analysis.analyze(event);
            synchronized (lock) {
                active.set(false);
                heartbeat.cancel(false);
                if (heartbeatFailure.get() != null) {
                    throw heartbeatFailure.get();
                }
                events.complete(claim);
            }
        } catch (RuntimeException failure) {
            synchronized (lock) {
                active.set(false);
                heartbeat.cancel(false);
                try {
                    events.fail(claim);
                } catch (RuntimeException persistenceFailure) {
                    log.warn("Could not persist retryable workflow event state: key={}, failureType={}",
                            claim.key(), persistenceFailure.getClass().getSimpleName());
                    failure.addSuppressed(persistenceFailure);
                }
            }
            throw failure;
        } finally {
            heartbeat.cancel(false);
            scheduler.shutdownNow();
        }
    }
}
