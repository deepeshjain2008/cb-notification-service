package com.igot.cb.notification.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.notification.entity.NotificationSettingEntity;
import com.igot.cb.notification.repository.NotificationSettingRepository;
import com.igot.cb.transactional.redis.cache.CacheService;
import com.igot.cb.util.Constants;

import org.igot.common.ApiResponse;
import org.igot.common.auth.AccessTokenValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class UserNotificationSettingServiceImplTest {

    @Mock
    private AccessTokenValidator accessTokenValidator;

    @Mock
    private CacheService cacheService;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private NotificationSettingRepository notificationSettingRepository;

    @InjectMocks
    private UserNotificationSettingServiceImpl service;

    private final String token = "dummy-token";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private ObjectNode createValidRequest(ObjectMapper realMapper, String type, boolean enabled) {
        ObjectNode request = realMapper.createObjectNode();
        request.put(Constants.NOTIFICATION_TYPE, type);
        request.put(Constants.ENABLED, enabled);

        ObjectNode root = realMapper.createObjectNode();
        root.set(Constants.REQUEST, request);
        return root;
    }

    @Test
    void testUpsertUserNotificationSetting_createsNewSetting() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "EMAIL"))
                .thenReturn(Optional.empty());
        when(notificationSettingRepository.save(any(NotificationSettingEntity.class)))
                .thenAnswer(inv -> {
                    NotificationSettingEntity e = inv.getArgument(0);
                    e.setId(1L);
                    return e;
                });

        ApiResponse response = service.upsertUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals("EMAIL", ((Map<?, ?>) response.getResult()).get(Constants.NOTIFICATION_TYPE));
        verify(notificationSettingRepository).save(any(NotificationSettingEntity.class));
    }

    @Test
    void testUpsertUserNotificationSetting_updatesExistingSetting() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", false);

        NotificationSettingEntity existing = NotificationSettingEntity.builder()
                .id(1L).userId("user1").notificationType("EMAIL")
                .enabled(true).createdAt(LocalDateTime.now(ZoneOffset.UTC))
                .updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .isDeleted(false).build();

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "EMAIL"))
                .thenReturn(Optional.of(existing));
        when(notificationSettingRepository.save(any(NotificationSettingEntity.class)))
                .thenReturn(existing);

        ApiResponse response = service.upsertUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(false, ((Map<?, ?>) response.getResult()).get(Constants.ENABLED));
        verify(notificationSettingRepository).save(existing);
    }

    @Test
    void testUpsertUserNotificationSetting_invalidUserId() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("");

        ApiResponse response = service.upsertUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testUpsertUserNotificationSetting_invalidPayload() {
        ObjectMapper realMapper = new ObjectMapper();
        ObjectNode invalidPayload = realMapper.createObjectNode(); // missing "request"

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");

        ApiResponse response = service.upsertUserNotificationSetting(invalidPayload, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testGetUserNotificationSettings_returnsMergedDefaults() {
        NotificationSettingEntity entity = NotificationSettingEntity.builder()
                .id(1L).userId("user1").notificationType("EMAIL").enabled(false)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .isDeleted(false).build();

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndIsDeletedFalse("user1"))
                .thenReturn(List.of(entity));

        ApiResponse response = service.getUserNotificationSettings(token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertTrue(result.containsKey(Constants.SETTINGS));
    }

    @Test
    void testGetUserNotificationSettings_mergesExistingSettingForKnownType() {
        // notificationType must match an actual NotificationType enum constant (e.g. IN_APP)
        // so that existingMap.get(type.name()) resolves to a non-null setting.
        NotificationSettingEntity entity = NotificationSettingEntity.builder()
                .id(1L).userId("user1").notificationType("IN_APP").enabled(false)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .isDeleted(false).build();

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndIsDeletedFalse("user1"))
                .thenReturn(List.of(entity));

        ApiResponse response = service.getUserNotificationSettings(token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> settings = (List<?>) result.get(Constants.SETTINGS);
        assertTrue(settings.stream().anyMatch(s -> {
            Map<?, ?> m = (Map<?, ?>) s;
            return "IN_APP".equals(m.get(Constants.NOTIFICATION_TYPE)) && Boolean.FALSE.equals(m.get(Constants.ENABLED));
        }));
    }

    @Test
    void testGetUserNotificationSettings_invalidUser() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("");

        ApiResponse response = service.getUserNotificationSettings(token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testDeleteUserNotificationSetting_existingSetting() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        NotificationSettingEntity entity = NotificationSettingEntity.builder()
                .id(1L).userId("user1").notificationType("EMAIL").enabled(true)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .isDeleted(false).build();

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "EMAIL"))
                .thenReturn(Optional.of(entity));
        when(notificationSettingRepository.save(any(NotificationSettingEntity.class)))
                .thenReturn(entity);

        ApiResponse response = service.deleteUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertTrue(((Map<?, ?>) response.getResult()).containsKey(Constants.DELETED));
    }

    @Test
    void testDeleteUserNotificationSetting_notFound() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "EMAIL"))
                .thenReturn(Optional.empty());

        ApiResponse response = service.deleteUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
    }

    @Test
    void testDeleteUserNotificationSetting_invalidUserId() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("");

        ApiResponse response = service.deleteUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testDeleteUserNotificationSetting_invalidPayload() {
        ObjectMapper realMapper = new ObjectMapper();
        ObjectNode invalidPayload = realMapper.createObjectNode(); // missing "request"

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");

        ApiResponse response = service.deleteUserNotificationSetting(invalidPayload, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testUpsertUserNotificationSetting_repositoryThrowsException() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "EMAIL"))
                .thenReturn(Optional.empty());
        when(notificationSettingRepository.save(any(NotificationSettingEntity.class)))
                .thenThrow(new RuntimeException("DB failure"));

        ApiResponse response = service.upsertUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testGetUserNotificationSettings_repositoryThrowsException() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndIsDeletedFalse("user1"))
                .thenThrow(new RuntimeException("DB failure"));

        ApiResponse response = service.getUserNotificationSettings(token);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testDeleteUserNotificationSetting_repositoryThrowsException() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode payload = createValidRequest(realMapper, "EMAIL", true);

        NotificationSettingEntity entity = NotificationSettingEntity.builder()
                .id(1L).userId("user1").notificationType("EMAIL").enabled(true)
                .createdAt(LocalDateTime.now(ZoneOffset.UTC)).updatedAt(LocalDateTime.now(ZoneOffset.UTC))
                .isDeleted(false).build();

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "EMAIL"))
                .thenReturn(Optional.of(entity));
        when(notificationSettingRepository.save(any(NotificationSettingEntity.class)))
                .thenThrow(new RuntimeException("DB failure"));

        ApiResponse response = service.deleteUserNotificationSetting(payload, token);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testValidateAndExtractPayload_missingNotificationType() {
        ObjectMapper realMapper = new ObjectMapper();
        ObjectNode request = realMapper.createObjectNode();
        // no notificationType
        request.put(Constants.ENABLED, true);

        ObjectNode root = realMapper.createObjectNode();
        root.set(Constants.REQUEST, request);

        when(accessTokenValidator.fetchUserIdFromAccessToken(token)).thenReturn("user1");

        ApiResponse response = service.upsertUserNotificationSetting(root, token);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

}
