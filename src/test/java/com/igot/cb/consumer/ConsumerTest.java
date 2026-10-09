package com.igot.cb.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.notification.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class ConsumerTest {

    @InjectMocks
    private Consumer consumer;

    @Mock
    private NotificationService notificationService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        // No logger mocking needed; do not set static final logger field.
    }


    @Test
    void testConsume_messageMissingRequest_doesNotCallService() {
        // Message with no "request" field
        String message = "{ \"foo\": \"bar\" }";
        Consumer spyConsumer = Mockito.spy(consumer);
        ReflectionTestUtils.setField(spyConsumer, "objectMapper", objectMapper);

        spyConsumer.consume(message);

        verify(notificationService, never()).bulkCreateNotifications(any());
    }

    @Test
    void testConsume_invalidJson_logsError() {
        String invalidMessage = "{ invalid json ";
        Consumer spyConsumer = Mockito.spy(consumer);
        ReflectionTestUtils.setField(spyConsumer, "objectMapper", objectMapper);

        spyConsumer.consume(invalidMessage);

        verify(notificationService, never()).bulkCreateNotifications(any());
    }

    @Test
    void testConsume_serviceThrowsException_logsError() {
        String message = "{ \"request\": { \"user_ids\": [{\"user_id\": \"user1\"}] } }";

        Consumer spyConsumer = Mockito.spy(consumer);
        ReflectionTestUtils.setField(spyConsumer, "objectMapper", objectMapper);

        doThrow(new RuntimeException("test exception"))
                .when(notificationService).bulkCreateNotifications(any(JsonNode.class));

        spyConsumer.consume(message);

        verify(notificationService, times(1)).bulkCreateNotifications(any(JsonNode.class));
    }

    @Test
    void testConsume_happyPath_forwardsToServiceDirectly() {
        String message = "{ \"request\": { \"user_ids\": [{\"user_id\": \"user1\"}] } }";
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);

        consumer.consume(message);

        verify(notificationService, times(1)).bulkCreateNotifications(any(JsonNode.class));
    }

    @Test
    void testConsume_serviceThrowsException_directInvocation_logsError() {
        String message = "{ \"request\": { \"user_ids\": [{\"user_id\": \"user1\"}] } }";
        ReflectionTestUtils.setField(consumer, "objectMapper", objectMapper);

        doThrow(new RuntimeException("direct test exception"))
                .when(notificationService).bulkCreateNotifications(any(JsonNode.class));

        consumer.consume(message);

        verify(notificationService, times(1)).bulkCreateNotifications(any(JsonNode.class));
    }
}