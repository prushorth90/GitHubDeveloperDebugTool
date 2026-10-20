package com.githubci.intelligence.queue;

import com.githubci.intelligence.dto.WorkflowEvent;

public interface WorkflowEventPublisher {
    void publish(WorkflowEvent event);
}
