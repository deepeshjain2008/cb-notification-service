package com.igot.cb.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.notification.service.NotificationService;
import com.igot.cb.producer.Producer;
import com.igot.cb.util.CbServerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PeerEvaluationStatusConsumerTest {

    @Mock
    private NotificationService notificationService;

    @Mock
    private Producer producer;

    @Mock
    private CbServerProperties cbServerProperties;

    private PeerEvaluationStatusConsumer consumer;

    private static final String ERROR_TOPIC = "dev.peer.evaluation.status.update.error";

    private static final String VALID_APPROVED_MESSAGE =
            "{\"userId\":\"user1\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"APPROVED\",\"subCategory\":\"PEER_REVIEW_ASSIGNED\"}";

    private static final String VALID_REJECTED_MESSAGE =
            "{\"userId\":\"user1\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"REJECTED\",\"subCategory\":\"PEER_REVIEW_ASSIGNED\"}";

    @BeforeEach
    void setUp() {
        consumer = new PeerEvaluationStatusConsumer(notificationService, new ObjectMapper(), producer, cbServerProperties);
    }

    @Test
    void consumeEvaluationUpdate_blankMessage_doesNotProcess() {
        consumer.consumeEvaluationUpdate("   ");
        verify(notificationService, after(500).never()).updatePeerEvaluationStatus(any(), any(), any(), any());
        verify(producer, after(500).never()).push(anyString(), any());
    }

    @Test
    void consumeEvaluationUpdate_nullMessage_doesNotProcess() {
        consumer.consumeEvaluationUpdate(null);
        verify(notificationService, after(500).never()).updatePeerEvaluationStatus(any(), any(), any(), any());
        verify(producer, after(500).never()).push(anyString(), any());
    }

    @Test
    void consumeEvaluationUpdate_validApprovedMessage_callsService() {
        consumer.consumeEvaluationUpdate(VALID_APPROVED_MESSAGE);
        verify(notificationService, timeout(2000).times(1))
                .updatePeerEvaluationStatus("user1", "notif1", "2024-01-01T00:00:00Z", "APPROVED");
    }

    @Test
    void consumeEvaluationUpdate_validRejectedMessage_callsService() {
        consumer.consumeEvaluationUpdate(VALID_REJECTED_MESSAGE);
        verify(notificationService, timeout(2000).times(1))
                .updatePeerEvaluationStatus("user1", "notif1", "2024-01-01T00:00:00Z", "REJECTED");
    }

    @Test
    void consumeEvaluationUpdate_invalidJson_pushesToErrorTopic() {
        when(cbServerProperties.getKafkaTopicPeerEvaluationError()).thenReturn(ERROR_TOPIC);
        consumer.consumeEvaluationUpdate("{ not valid json }");
        verify(producer, timeout(2000).times(1)).push(eq(ERROR_TOPIC), any());
        verify(notificationService, never()).updatePeerEvaluationStatus(any(), any(), any(), any());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{\"userId\":\"user1\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"subCategory\":\"PEER_REVIEW_ASSIGNED\"}",
            "{\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"APPROVED\",\"subCategory\":\"PEER_REVIEW_ASSIGNED\"}",
            "{\"userId\":\"\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"APPROVED\",\"subCategory\":\"PEER_REVIEW_ASSIGNED\"}",
            "{\"userId\":\"user1\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"SUBMITTED\",\"subCategory\":\"PEER_REVIEW_ASSIGNED\"}",
            "{\"userId\":\"user1\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"APPROVED\"}",
            "{\"userId\":\"user1\",\"notificationId\":\"notif1\",\"createdAt\":\"2024-01-01T00:00:00Z\",\"status\":\"APPROVED\",\"subCategory\":\"PEER_EVALUATION_ASSIGNED\"}"
    })
    void consumeEvaluationUpdate_invalidPayload_doesNotCallService(String message) {
        consumer.consumeEvaluationUpdate(message);
        verify(notificationService, after(500).never()).updatePeerEvaluationStatus(any(), any(), any(), any());
        verify(producer, after(500).never()).push(anyString(), any());
    }

    @Test
    void consumeEvaluationUpdate_serviceThrowsException_pushesToErrorTopic() {
        when(cbServerProperties.getKafkaTopicPeerEvaluationError()).thenReturn(ERROR_TOPIC);
        doThrow(new RuntimeException("DB error"))
                .when(notificationService)
                .updatePeerEvaluationStatus(anyString(), anyString(), anyString(), anyString());
        consumer.consumeEvaluationUpdate(VALID_APPROVED_MESSAGE);
        verify(producer, timeout(2000).times(1)).push(eq(ERROR_TOPIC), any());
    }

    @Test
    void consumeEvaluationUpdate_asyncDispatchThrows_pushesToErrorTopicSynchronously() {
        when(cbServerProperties.getKafkaTopicPeerEvaluationError()).thenReturn(ERROR_TOPIC);
        try (MockedStatic<CompletableFuture> mockedStatic = mockStatic(CompletableFuture.class)) {
            mockedStatic.when(() -> CompletableFuture.runAsync(any(Runnable.class)))
                    .thenThrow(new RuntimeException("dispatch failure"));
            consumer.consumeEvaluationUpdate(VALID_APPROVED_MESSAGE);
        }
        verify(producer, times(1)).push(eq(ERROR_TOPIC), any());
        verify(notificationService, never()).updatePeerEvaluationStatus(any(), any(), any(), any());
    }

    @Test
    void evaluationUpdateRequestBuilder_toString_isCovered() throws Exception {
        Class<?> requestClass = Class.forName("com.igot.cb.consumer.PeerEvaluationStatusConsumer$EvaluationUpdateRequest");
        Method builderMethod = requestClass.getDeclaredMethod("builder");
        builderMethod.setAccessible(true);
        Object builder = builderMethod.invoke(null);
        String result = builder.toString();
        assertNotNull(result);
        assertTrue(result.contains("EvaluationUpdateRequestBuilder"));
    }
}
