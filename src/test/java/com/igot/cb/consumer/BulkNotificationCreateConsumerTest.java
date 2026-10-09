package com.igot.cb.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.notification.service.NotificationService;
import com.igot.cb.producer.Producer;
import com.igot.cb.util.CbServerProperties;
import org.igot.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BulkNotificationCreateConsumerTest {

    @Mock
    private NotificationService notificationService;
    @Mock
    private Producer producer;
    @Mock
    private CbServerProperties cbServerProperties;

    private BulkNotificationCreateConsumer consumer;

    private static final String ERROR_TOPIC = "dev.notification.bulk.create.error";
    private static final String VALID_MESSAGE =
            "{\"request\":{\"notifications\":[{\"userId\":\"user1\",\"category\":\"Test\"}]}}";

    @BeforeEach
    void setUp() {
        consumer = new BulkNotificationCreateConsumer(notificationService, new ObjectMapper(), producer, cbServerProperties);
    }

    @Test
    void consumeBulkCreate_blankMessage_doesNotCallService() {
        consumer.consumeBulkCreate("   ");
        verify(notificationService, after(500).never()).bulkCreatePeerValidationNotifications(any());
        verify(producer, after(500).never()).push(anyString(), any());
    }

    @Test
    void consumeBulkCreate_nullMessage_doesNotCallService() {
        consumer.consumeBulkCreate(null);
        verify(notificationService, after(500).never()).bulkCreatePeerValidationNotifications(any());
        verify(producer, after(500).never()).push(anyString(), any());
    }

    @Test
    void consumeBulkCreate_validMessage_invokesService() {
        when(notificationService.bulkCreatePeerValidationNotifications(any())).thenReturn(new ApiResponse());
        consumer.consumeBulkCreate(VALID_MESSAGE);
        verify(notificationService, timeout(2000).times(1)).bulkCreatePeerValidationNotifications(any());
    }

    @Test
    void consumeBulkCreate_invalidJson_pushesToErrorTopic() {
        when(cbServerProperties.getKafkaTopicNotificationBulkCreateError()).thenReturn(ERROR_TOPIC);
        consumer.consumeBulkCreate("{ not valid json }");
        verify(producer, timeout(2000).times(1)).push(eq(ERROR_TOPIC), any());
        verify(notificationService, never()).bulkCreatePeerValidationNotifications(any());
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {
            "{}",
            "{\"notifications\":[{\"userId\":\"user1\"}]}",
            "{\"data\":{\"request\":{\"userId\":\"user1\"}}}"
    })
    void consumeBulkCreate_missingRequestField_doesNotCallService(String message) {
        consumer.consumeBulkCreate(message);
        verify(notificationService, after(500).never()).bulkCreatePeerValidationNotifications(any());
        verify(producer, after(500).never()).push(anyString(), any());
    }

    @Test
    void consumeBulkCreate_serviceThrowsException_pushesToErrorTopic() {
        when(cbServerProperties.getKafkaTopicNotificationBulkCreateError()).thenReturn(ERROR_TOPIC);
        when(notificationService.bulkCreatePeerValidationNotifications(any()))
                .thenThrow(new RuntimeException("DB error"));
        consumer.consumeBulkCreate(VALID_MESSAGE);
        verify(producer, timeout(2000).times(1)).push(eq(ERROR_TOPIC), any());
    }

    @Test
    void consumeBulkCreate_asyncDispatchThrows_pushesToErrorTopicSynchronously() {
        when(cbServerProperties.getKafkaTopicNotificationBulkCreateError()).thenReturn(ERROR_TOPIC);
        try (MockedStatic<CompletableFuture> mockedStatic = mockStatic(CompletableFuture.class)) {
            mockedStatic.when(() -> CompletableFuture.runAsync(any(Runnable.class)))
                    .thenThrow(new RuntimeException("dispatch failure"));
            consumer.consumeBulkCreate(VALID_MESSAGE);
        }
        verify(producer, times(1)).push(eq(ERROR_TOPIC), any());
        verify(notificationService, never()).bulkCreatePeerValidationNotifications(any());
    }
}