package com.githubci.intelligence.queue;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.services.WorkflowEventWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;

@Component
@ConditionalOnProperty(name = "app.sqs.worker-enabled", havingValue = "true")
public class SqsWorkflowEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(SqsWorkflowEventConsumer.class);
    private final SqsClient client;
    private final ObjectMapper mapper;
    private final WorkflowEventWorker worker;
    private final String queueUrl;
    private final int visibilitySeconds;

    public SqsWorkflowEventConsumer(SqsClient client, ObjectMapper mapper, WorkflowEventWorker worker,
            @Value("${app.sqs.queue-url}") String queueUrl,
            @Value("${app.sqs.visibility-seconds}") int visibilitySeconds) {
        if (queueUrl.isBlank() || visibilitySeconds < 30 || visibilitySeconds > 43200) {
            throw new IllegalArgumentException("SQS worker requires a queue URL and visibility between 30 and 43200 seconds");
        }
        this.client = client;
        this.mapper = mapper;
        this.worker = worker;
        this.queueUrl = queueUrl;
        this.visibilitySeconds = visibilitySeconds;
    }

    @Scheduled(fixedDelayString = "${app.sqs.poll-delay:1000}")
    public void poll() {
        try {
            var response = client.receiveMessage(request -> request.queueUrl(queueUrl)
                    .maxNumberOfMessages(1).waitTimeSeconds(20).visibilityTimeout(visibilitySeconds)
                    .messageSystemAttributeNames(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT));
            for (Message message : response.messages()) {
                consume(message);
            }
        } catch (RuntimeException failure) {
            log.error("SQS receive failed: failureType={}", failure.getClass().getSimpleName());
        }
    }

    public void consume(Message message) {
        try {
            WorkflowEvent event = mapper.readerFor(WorkflowEvent.class)
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readValue(message.body());
            if (event == null) {
                throw new IllegalArgumentException("Missing workflow event");
            }
            worker.process(event, () -> client.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                    .receiptHandle(message.receiptHandle()).visibilityTimeout(visibilitySeconds)));
            client.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException failure) {
            int receives = receiveCount(message);
            // Native SQS redrive moves poison/exhausted messages to the DLQ; never delete on failure.
            log.warn("Workflow message not acknowledged: messageId={}, receiveCount={}, failureType={}",
                    message.messageId(), receives, failure.getClass().getSimpleName());
            try {
                int delay = (int) Math.min(900L, 30L << Math.min(receives - 1, 5));
                client.changeMessageVisibility(request -> request.queueUrl(queueUrl)
                        .receiptHandle(message.receiptHandle()).visibilityTimeout(delay));
            } catch (RuntimeException retryFailure) {
                log.warn("SQS retry delay failed; existing visibility timeout remains: failureType={}",
                        retryFailure.getClass().getSimpleName());
            }
        }
    }

    private static int receiveCount(Message message) {
        String count = message.attributes().get(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT);
        if (count == null) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(count));
        } catch (NumberFormatException invalid) {
            log.warn("SQS returned invalid receive count");
            return 1;
        }
    }
}
