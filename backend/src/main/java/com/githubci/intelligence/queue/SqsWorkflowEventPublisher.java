package com.githubci.intelligence.queue;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.WebhookException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.ObjectMapper;

@Component
public class SqsWorkflowEventPublisher implements WorkflowEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(SqsWorkflowEventPublisher.class);
    private final SqsClient client;
    private final ObjectMapper mapper;
    private final String queueUrl;

    public SqsWorkflowEventPublisher(SqsClient client, ObjectMapper mapper,
            @Value("${app.sqs.queue-url}") String queueUrl) {
        this.client = client;
        this.mapper = mapper;
        this.queueUrl = queueUrl;
    }

    @Override
    public void publish(WorkflowEvent event) {
        event.validate();
        if (queueUrl.isBlank()) {
            throw unavailable();
        }
        try {
            var response = client.sendMessage(request -> request.queueUrl(queueUrl)
                    .messageBody(mapper.writeValueAsString(event)));
            if (response.messageId() == null || response.messageId().isBlank()
                    || !response.sdkHttpResponse().isSuccessful()) {
                throw unavailable();
            }
        } catch (SdkException failure) {
            log.warn("SQS publication failed: key={}, failureType={}",
                    event.idempotencyKey(), failure.getClass().getSimpleName());
            throw unavailable();
        }
    }

    private static WebhookException unavailable() {
        return new WebhookException(HttpStatus.SERVICE_UNAVAILABLE, "Could not enqueue workflow event");
    }
}
