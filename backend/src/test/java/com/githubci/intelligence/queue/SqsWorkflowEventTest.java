package com.githubci.intelligence.queue;

import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import com.githubci.intelligence.dto.WorkflowEvent;
import com.githubci.intelligence.exceptions.WebhookException;
import com.githubci.intelligence.services.WorkflowEventWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.http.SdkHttpResponse;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageSystemAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SqsWorkflowEventTest {

    private static final String QUEUE = "http://localhost/queue";
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final WorkflowEvent event = new WorkflowEvent(1, "octo/demo", null, 42, 2,
            "failure", UUID.randomUUID().toString());
    private SqsClient client;
    private WorkflowEventWorker worker;
    private SqsWorkflowEventConsumer consumer;

    @BeforeEach
    void setUp() {
        client = mock(SqsClient.class);
        worker = mock(WorkflowEventWorker.class);
        consumer = new SqsWorkflowEventConsumer(client, mapper, worker, QUEUE, 120);
    }

    @Test
    void publisherWaitsForAcceptedMessageAndSerializesOnlyEvent() {
        var response = SendMessageResponse.builder().messageId("accepted");
        response.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        when(client.sendMessage(anyRequest())).thenReturn(response.build());
        new SqsWorkflowEventPublisher(client, mapper, QUEUE).publish(event);
        ArgumentCaptor<Consumer<SendMessageRequest.Builder>> request = ArgumentCaptor.captor();
        verify(client).sendMessage(request.capture());
        var builder = SendMessageRequest.builder();
        request.getValue().accept(builder);
        assertEquals(QUEUE, builder.build().queueUrl());
        assertEquals(event, mapper.readValue(builder.build().messageBody(), WorkflowEvent.class));
    }

    @Test
    void failedAndAmbiguousPublicationAreNotAccepted() {
        var publisher = new SqsWorkflowEventPublisher(client, mapper, QUEUE);
        when(client.sendMessage(anyRequest()))
                .thenThrow(SdkClientException.create("private transport data"));
        assertEquals(503, assertThrows(WebhookException.class, () -> publisher.publish(event)).getStatus().value());
        var response = SendMessageResponse.builder();
        response.sdkHttpResponse(SdkHttpResponse.builder().statusCode(200).build());
        doReturn(response.build()).when(client).sendMessage(anyRequest());
        assertThrows(WebhookException.class, () -> publisher.publish(event));
        assertThrows(WebhookException.class, () ->
                new SqsWorkflowEventPublisher(client, mapper, "").publish(event));
    }

    @Test
    void deletesOnlyAfterWorkerCompletes() {
        consumer.consume(message(mapper.writeValueAsString(event), 1));
        var order = inOrder(worker, client);
        order.verify(worker).process(eq(event), any(Runnable.class));
        order.verify(client).deleteMessage(anyRequest());
    }

    @Test
    void failureLeavesMessageAndAppliesCappedExponentialDelay() {
        doThrow(new IllegalStateException("private logs")).when(worker).process(any(), any());
        consumer.consume(message(mapper.writeValueAsString(event), 10));
        verify(client, never()).deleteMessage(anyRequest());
        ArgumentCaptor<Consumer<ChangeMessageVisibilityRequest.Builder>> request = ArgumentCaptor.captor();
        verify(client).changeMessageVisibility(request.capture());
        var builder = ChangeMessageVisibilityRequest.builder();
        request.getValue().accept(builder);
        assertEquals(900, builder.build().visibilityTimeout());
        assertEquals("receipt", builder.build().receiptHandle());
    }

    @Test
    void poisonMessagesRemainForNativeDeadLetterRedrive() {
        for (String body : new String[] {"broken", "null", mapper.writeValueAsString(event) + " {}"}) {
            consumer.consume(message(body, 1));
        }
        verifyNoInteractions(worker);
        verify(client, never()).deleteMessage(anyRequest());
        verify(client, times(3)).changeMessageVisibility(anyRequest());
    }

    @Test
    void failedDeleteRemainsRetryable() {
        doThrow(SdkClientException.create("delete timeout")).when(client).deleteMessage(anyRequest());
        assertDoesNotThrow(() -> consumer.consume(message(mapper.writeValueAsString(event), 1)));
        verify(worker).process(eq(event), any(Runnable.class));
        verify(client).changeMessageVisibility(anyRequest());
    }

    private static <T> Consumer<T> anyRequest() {
        return any();
    }

    private static Message message(String body, int receives) {
        return Message.builder().messageId("message").receiptHandle("receipt").body(body)
                .attributes(Map.of(MessageSystemAttributeName.APPROXIMATE_RECEIVE_COUNT, Integer.toString(receives)))
                .build();
    }
}
