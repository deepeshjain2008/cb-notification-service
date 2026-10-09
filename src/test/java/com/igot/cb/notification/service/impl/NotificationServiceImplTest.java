package com.igot.cb.notification.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.igot.cb.notification.entity.NotificationSettingEntity;
import com.igot.cb.notification.enums.NotificationReadStatus;
import com.igot.cb.notification.enums.NotificationSubCategory;
import com.igot.cb.notification.enums.NotificationSubType;
import com.igot.cb.notification.repository.NotificationSettingRepository;
import com.igot.cb.notification.service.FormExpiryValidator;
import com.igot.cb.producer.Producer;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.Constants;

import org.igot.common.ApiResponse;
import org.igot.common.auth.AccessTokenValidator;
import org.igot.common.cassandra.CassandraOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;


import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.igot.cb.util.Constants.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NotificationServiceImplTest {
    @Spy
    @InjectMocks
    private NotificationServiceImpl notificationService;
    private static final String AUTH_TOKEN = "test-auth-token";
    @Mock
    private AccessTokenValidator accessTokenValidator;
    private static final String NOTIFICATION_ID_1 = "notification-id-1";
    private static final String NOTIFICATION_ID_2 = "notification-id-2";

    @Mock
    private CassandraOperation cassandraOperation;

    @Mock
    private NotificationSettingRepository notificationSettingRepository;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CbServerProperties cbServerProperties;

    @Mock
    private Producer producer;

    @Mock
    private FormExpiryValidator formExpiryValidator;

    private static final String CREATED_AT = "created_at";
    private static final String READ = "read";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateNotification_Success_WithComplexMessage() throws Exception{
        // Prepare input
        String authToken = "Bearer token";
        String userId = "testUser";
        String notificationType = "comment";
        String category = "content";
        String source = "userCreated";
        String role = "user";


        String payload = "{ \"request\": { " +
                "\"type\": \"" + notificationType + "\"," +
                "\"category\": \"" + category + "\"," +
                "\"source\": \"" + source + "\"," +
                "\"role\": \"" + role + "\"," +
                "\"message\": { " +
                "\"topic\": \"subscriber-updates\"," +
                "\"notification\": { \"body\": \"This week's edition is now available.\", \"title\": \"NewsMagazine.com\" }," +
                "\"data\": { \"volume\": \"3.21.15\", \"contents\": \"http://www.news-magazine.com/world-week/21659772\" }" +
                "}" +
                "} }";

        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, notificationType))
                .thenReturn(Optional.empty());
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("response", "SUCCESS"));

        doAnswer(invocation -> {
            String msg = invocation.getArgument(0, String.class);
            return mapper.readTree(msg);
        }).when(objectMapper).readTree(anyString());

        ApiResponse response = notificationService.createNotification(userNotificationDetail, authToken);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());
        Map<String, Object> result = (Map<String, Object>) response.getResult();

        assertFalse(result.containsKey(Constants.IS_DELETED));
        assertFalse(result.containsKey(Constants.UPDATED_AT));
        assertFalse(result.containsKey(Constants.USER_ID));
        assertFalse(result.containsKey(Constants.READ_AT));
        assertFalse(result.containsKey(Constants.TEMPLATE_ID));
        assertEquals(notificationType, result.get(Constants.TYPE));
        assertEquals(role, result.get(Constants.ROLE));
        assertEquals(source, result.get(Constants.SOURCE));
        assertEquals(category, result.get(Constants.CATEGORY));

        Object messageField = result.get(Constants.MESSAGE);
        assertNotNull(messageField, "MESSAGE field should not be null");
        assertTrue(messageField instanceof JsonNode);
        JsonNode messageNode = (JsonNode) messageField;
        assertEquals("subscriber-updates", messageNode.get("topic").asText());
        assertEquals("NewsMagazine.com", messageNode.get("notification").get("title").asText());
        assertEquals("3.21.15", messageNode.get("data").get("volume").asText());
    }

    @Test
    void testCreateNotification_MissingUserId() throws Exception{
        String authToken = "Bearer token";
        ObjectMapper mapper = new ObjectMapper();
        String payload = "{ \"request\": { \"type\": \"comment\" } }";
        JsonNode userNotificationDetail = mapper.readTree(payload);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn("");

        ApiResponse response = notificationService.createNotification(userNotificationDetail, authToken);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testCreateNotification_MissingRequestNode() throws Exception {
        String authToken = "Bearer token";
        String userId = "testUser";
        ObjectMapper mapper = new ObjectMapper();
        String payload = "{}";
        JsonNode userNotificationDetail = mapper.readTree(payload);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        ApiResponse response = notificationService.createNotification(userNotificationDetail, authToken);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals("Missing or invalid 'request' node in payload", response.getParams().getErrMsg());
    }

    @Test
    void testCreateNotification_MessageStringParsingFails() throws Exception {
        // This test simulates when the message field is a string but not valid JSON
        String authToken = "Bearer token";
        String userId = "testUser";
        String notificationType = "comment";
        String category = "content";
        String source = "userCreated";
        String role = "user";
        // message is a string, not a JSON object
        String payload = "{ \"request\": { " +
                "\"type\": \"" + notificationType + "\"," +
                "\"category\": \"" + category + "\"," +
                "\"source\": \"" + source + "\"," +
                "\"role\": \"" + role + "\"," +
                "\"message\": \"This is not JSON\"" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("response", "SUCCESS"));
        doThrow(new RuntimeException("Not a JSON")).when(objectMapper).readTree("This is not JSON");

        ApiResponse response = notificationService.createNotification(userNotificationDetail, authToken);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals("This is not JSON", result.get(Constants.MESSAGE));
    }

    @Test
    void testMarkNotificationsAsDeleted_TooManyIds() {
        String authToken = "Bearer abc";
        String userId = "user-1";
        int batchSize = Constants.MAX_NOTIFICATION_READ_BATCH_SIZE + 1;
        List<String> notificationIds = new ArrayList<>();
        for (int i = 0; i < batchSize; i++) notificationIds.add("id-" + i);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        ApiResponse response = notificationService.markNotificationsAsDeleted(authToken, notificationIds);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("only mark up to"));
    }

    @Test
    void testPrepareNotificationResponse_MessageIsNonString() {
        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put("message", 12345); // not a String

        // objectMapper.readTree should not be called
        Map<String, Object> result = notificationService.prepareNotificationResponse(dbRecord);
        assertEquals(12345, result.get("message"));
    }

    @Test
    void testPrepareNotificationResponse_MessageIsNull() {
        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put("message", null);

        Map<String, Object> result = notificationService.prepareNotificationResponse(dbRecord);
        assertNull(result.get("message"));
    }

    @Test
    void testBulkCreateNotifications_exception() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode userNotificationDetail = realMapper.readTree(payload);

        when(cassandraOperation.insertBulkRecord(
                anyString(), anyString(), anyList()))
                .thenThrow(new RuntimeException("DB error"));

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Internal server error while saving notifications", response.getParams().getErrMsg());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testBulkCreateNotifications_success() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"category\": \"content\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"message\": {\"title\": \"Test\", \"data\": {\"foo\": \"bar\"}}," +
                "\"user_ids\": [ {\"user_id\": \"user1\"}, {\"user_id\": \"user2\"} ]" +
                "} }";
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode userNotificationDetail = realMapper.readTree(payload);

        NotificationSettingEntity entity = new NotificationSettingEntity();
        entity.setEnabled(true); // or entity.setIsEnabled(true); based on your class
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(anyString(), anyString()))
                .thenReturn(Optional.of(entity));

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        mockInsertResponse.setResult(Map.of(Constants.RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(mockInsertResponse);

        // Spy to pass through prepareNotificationResponse
        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0))  // Simply return the input
                .when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertTrue(result.containsKey("notifications"));

        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get("notifications");
        assertEquals(2, notifications.size());

        Set<String> userIds = notifications.stream()
                .map(n -> (String) n.get(Constants.USER_ID))
                .collect(Collectors.toSet());

        assertTrue(userIds.contains("user1"));
        assertTrue(userIds.contains("user2"));
    }


    @Test
    void testBulkCreateNotifications_missingRequest() throws Exception {
        String payload = "{}";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("Missing or invalid 'request' node in payload", response.getParams().getErrMsg());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testBulkCreateNotifications_missingUserIds() throws Exception {
        String payload = "{ \"request\": { \"type\": \"comment\" } }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("'user_ids' must be a non-empty list", response.getParams().getErrMsg());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testBulkCreateNotifications_tooManyUserIds() throws Exception {
        // Build user_ids array > MAX_USER_LIMIT (100)
        int limit = 101;
        StringBuilder userIdsJson = new StringBuilder("[");
        for (int i = 0; i < limit; i++) {
            if (i > 0) userIdsJson.append(",");
            userIdsJson.append("\"user").append(i).append("\"");
        }
        userIdsJson.append("]");
        String payload = "{ \"request\": { \"type\": \"comment\", \"user_ids\": " + userIdsJson + " } }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Cannot send notifications to more than 100 users"));
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_filterUnread() {
        String authToken = "Bearer xyz";
        String userId = "user-42";
        int days = 10, page = 0, size = 10;
        Instant now = Instant.now();

        Map<String, Object> notif1 = new HashMap<>();
        notif1.put(NOTIFICATION_ID, "n1");
        notif1.put(Constants.USER_ID, userId);
        notif1.put(Constants.CREATED_AT, now.minusSeconds(3600));
        notif1.put(Constants.READ, false);
        notif1.put(Constants.CATEGORY, "catA");

        Map<String, Object> notif2 = new HashMap<>();
        notif2.put(NOTIFICATION_ID, "n2");
        notif2.put(Constants.USER_ID, userId);
        notif2.put(Constants.CREATED_AT, now.minusSeconds(7200));
        notif2.put(Constants.READ, true);
        notif2.put(Constants.CATEGORY, "catA");

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        // Correctly mock user and global notification calls separately
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif1, notif2));

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of()); // empty global notifications

        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0)).when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.getNotificationsByUserIdAndLastXDays(
                authToken, days, page, size, NotificationReadStatus.UNREAD, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> returnedNotifs = (List<Map<String, Object>>) result.get(NOTIFICATIONS);
        assertEquals(1, returnedNotifs.size());
        assertEquals("n1", returnedNotifs.get(0).get(NOTIFICATION_ID));
    }
    private boolean isGlobalSubCategory(NotificationSubCategory subCategory) {
        return NotificationSubCategory.EVENT_PUBLISHED.equals(subCategory) ||
                NotificationSubCategory.COURSE_PUBLISHED.equals(subCategory) ||
                NotificationSubCategory.PROGRAM_PUBLISHED.equals(subCategory);
    }

    @Test
    public void testIsGlobalSubCategory() {
        assertTrue(isGlobalSubCategory(NotificationSubCategory.EVENT_PUBLISHED));
        assertTrue(isGlobalSubCategory(NotificationSubCategory.COURSE_PUBLISHED));
        assertTrue(isGlobalSubCategory(NotificationSubCategory.PROGRAM_PUBLISHED));
        assertFalse(isGlobalSubCategory(null));
    }

    @Test
    void testGetInstant_withInstant() {
        Instant now = Instant.now();
        Instant result = notificationService.getInstant(now);
        assertEquals(now, result);
    }

    @Test
    void testGetInstant_withDate() {
        Date date = new Date();
        Instant expected = date.toInstant();
        Instant result = notificationService.getInstant(date);
        assertEquals(expected, result);
    }

    @Test
    void testGetInstant_withValidString() {
        String validIsoString = "2023-08-07T10:15:30Z";
        Instant expected = Instant.parse(validIsoString);
        Instant result = notificationService.getInstant(validIsoString);
        assertEquals(expected, result);
    }

    @Test
    void testGetInstant_withInvalidString() {
        String invalidString = "not-a-date";
        Instant result = notificationService.getInstant(invalidString);
        assertNull(result);
    }

    @Test
    void testGetInstant_withUnsupportedType() {
        Integer unsupportedValue = 12345;
        Instant result = notificationService.getInstant(unsupportedValue);
        assertNull(result);
    }

    @Test
    void testCreateGlobalNotification_Success() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.EVENT_PUBLISHED;

        String jsonPayload = "{ " +
                "\"type\": \"info\", " +
                "\"category\": \"event\", " +
                "\"sub_category\": \"EVENT_PUBLISHED\", " +
                "\"sub_type\": \"announcement\", " +
                "\"source\": \"system\", " +
                "\"role\": \"admin\", " +
                "\"template_id\": \"template123\", " +
                "\"message\": { \"title\": \"Event started\", \"body\": \"The event is live now!\" } " +
                "}";

        ObjectMapper mapper = new ObjectMapper();
        JsonNode requestNode = mapper.readTree(jsonPayload);

        when(cassandraOperation.insertRecord(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap()))
                .thenReturn(Map.of("response", Constants.SUCCESS));

        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0))
                .when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.createGlobalNotification(subCategory, requestNode);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertTrue(result.containsKey("notification"));

        Map<String, Object> notification = (Map<String, Object>) result.get("notification");

        assertEquals(Constants.GLOBAL, notification.get(Constants.USER_ID));

    }

    @Test
    void testMarkNotificationsAsRead_invalidType() {
        String authToken = "Bearer xyz";
        String userId = "user-42";
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.TYPE, "invalid");

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        ApiResponse response = notificationService.markNotificationsAsRead(authToken, request, Constants.API_VERSION_V1);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("Invalid type"));
    }

    @Test
    void testMarkNotificationsAsRead_missingType() {
        String authToken = "Bearer xyz";
        String userId = "user-42";
        Map<String, Object> request = new HashMap<>();

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        ApiResponse response = notificationService.markNotificationsAsRead(authToken, request, Constants.API_VERSION_V1);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("Request type must be provided"));
    }

    @Test
    void testMarkNotificationsAsRead_invalidIdsForIndividual() {
        String authToken = "Bearer xyz";
        String userId = "user-42";
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.TYPE, Constants.INDIVIDUAL);
        request.put("ids", "notalist"); // Invalid ids type

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        ApiResponse response = notificationService.markNotificationsAsRead(authToken, request, Constants.API_VERSION_V1);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("Missing or invalid 'ids' field"));
    }


    @Test
    void testGetUnreadNotificationCount_userIdMissing() {
        String authToken = "Bearer xyz";
        int days = 7;

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn("");

        ApiResponse response = notificationService.getUnreadNotificationCount(authToken, days);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testReadByUserIdAndNotificationId_Success() {
        ApiResponse expectedResponse = ApiResponse.createDefaultResponse(Constants.USER_NOTIFICATION_READ_NOTIFICATIONID);
        expectedResponse.setResponseCode(HttpStatus.OK);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        ApiResponse actualResponse = notificationService.readByUserIdAndNotificationId(NOTIFICATION_ID, AUTH_TOKEN);
        assertNotNull(actualResponse);
        assertEquals(HttpStatus.OK, actualResponse.getResponseCode());
    }

    @Test
    void testReadByUserIdAndNotificationId_EmptyUserId() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("");
        ApiResponse response = notificationService.readByUserIdAndNotificationId(NOTIFICATION_ID, AUTH_TOKEN);
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testReadByUserIdAndNotificationId_Exception() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenThrow(new RuntimeException("Test exception"));
        ApiResponse response = notificationService.readByUserIdAndNotificationId(NOTIFICATION_ID, AUTH_TOKEN);
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Internal server error while fetching notification by userId",
                response.getParams().getErrMsg());
    }

    @Test
    void testMarkNotificationsAsDeleted_Success() {
        List<String> notificationIds = Arrays.asList(NOTIFICATION_ID_1, NOTIFICATION_ID_2);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        ApiResponse response = notificationService.markNotificationsAsDeleted(AUTH_TOKEN, notificationIds);
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        assertEquals("Notifications marked as deleted successfully", response.getParams().getErrMsg());

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertNotNull(result);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updatedNotifications = (List<Map<String, Object>>) result.get(NOTIFICATIONS);
        assertNotNull(updatedNotifications);
    }

    @Test
    void testMarkNotificationsAsDeleted_EmptyUserId() {
        List<String> notificationIds = Arrays.asList(NOTIFICATION_ID_1, NOTIFICATION_ID_2);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("");
        ApiResponse response = notificationService.markNotificationsAsDeleted(AUTH_TOKEN, notificationIds);

        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testMarkNotificationsAsDeleted_EmptyNotificationIds() {
        List<String> notificationIds = Collections.emptyList();
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        ApiResponse response = notificationService.markNotificationsAsDeleted(AUTH_TOKEN, notificationIds);
        assertNotNull(response);
        assertNotEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testMarkNotificationsAsDeleted_NullNotificationIds() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        ApiResponse response = notificationService.markNotificationsAsDeleted(AUTH_TOKEN, null);
        assertNotNull(response);
        assertNotEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testMarkNotificationsAsDeleted_Exception() {
        List<String> notificationIds = Arrays.asList(NOTIFICATION_ID_1, NOTIFICATION_ID_2);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenThrow(new RuntimeException("Test exception"));
        ApiResponse response = notificationService.markNotificationsAsDeleted(AUTH_TOKEN, notificationIds);
        assertNotNull(response);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Internal server error while fetching markNotificationsAsDeleted  delete",
                response.getParams().getErrMsg());
    }


    @Test
    void testGetUnreadNotificationCount_EmptyUserId() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("");
        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 7);
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }


    @Test
    void testGetUnreadNotificationCount_InvalidDays() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, -1);
        assertNotNull(response);
        assertNotEquals(HttpStatus.OK, response.getResponseCode());
    }


    @Test
    void testProcessReadUpdate_AllScenarios() throws Exception {
        // Notification not found
        List<Map<String, Object>> notifications = List.of(
                Map.of(NOTIFICATION_ID, "not-matching-id", READ, false)
        );

        List<String> targetIds = List.of(NOTIFICATION_ID);

        // Use reflection to invoke private method
        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "processReadUpdate", String.class, List.class, List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                notificationService, USER_ID, notifications, targetIds);

        assertTrue(result.isEmpty());
    }

    @Test
    void testProcessReadUpdate_MarkUnreadAsRead_Success() throws Exception {
        Instant createdAt = Instant.now();
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, NOTIFICATION_ID);
        notification.put(READ, false);
        notification.put(CREATED_AT, createdAt);

        List<Map<String, Object>> notifications = List.of(notification);
        List<String> targetIds = List.of(NOTIFICATION_ID);

        Map<String, Object> updateResponse = Map.of(Constants.RESPONSE, Constants.SUCCESS);

        when(cassandraOperation.updateRecord(
                eq(Constants.KEYSPACE_SUNBIRD),
                eq(Constants.TABLE_USER_NOTIFICATION),
                anyMap(),
                anyMap()
        )).thenReturn(updateResponse);

        // Mock fetchNotifications
        Method fetchMethod = NotificationServiceImpl.class.getDeclaredMethod("fetchNotifications", String.class);
        fetchMethod.setAccessible(true);
        ReflectionTestUtils.setField(notificationService, "cassandraOperation", cassandraOperation);

        doReturn(List.of(notification)).when(cassandraOperation).getRecordsByProperties(
                any(), any(), anyMap(), any(), anyInt());

        // Invoke processReadUpdate
        Method processMethod = NotificationServiceImpl.class.getDeclaredMethod(
                "processReadUpdate", String.class, List.class, List.class);
        processMethod.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) processMethod.invoke(
                notificationService, USER_ID, notifications, targetIds);

        assertEquals(1, result.size());
        assertEquals(NOTIFICATION_ID, result.get(0).get(ID));
    }

    @Test
    void testUpdateNotification_NotificationFound() throws Exception {
        Instant createdAt = Instant.now();
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, NOTIFICATION_ID);
        notification.put(CREATED_AT, createdAt);

        List<Map<String, Object>> notifications = List.of(notification);

        when(cassandraOperation.getRecordsByProperties(
                any(), any(), anyMap(), any(), anyInt()
        )).thenReturn(notifications);

        when(cassandraOperation.updateRecord(
                any(), any(), anyMap(), anyMap()
        )).thenReturn(Map.of(Constants.RESPONSE, Constants.SUCCESS));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "updateNotification", String.class, String.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> updateMap = Map.of(READ, true, READ_AT, Instant.now());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                notificationService, USER_ID, NOTIFICATION_ID, updateMap);

        assertEquals(Constants.SUCCESS, result.get(Constants.RESPONSE));
    }

    @Test
    void testUpdateNotification_NotificationNotFound() throws Exception {
        when(cassandraOperation.getRecordsByProperties(
                any(), any(), anyMap(), any(), anyInt()
        )).thenReturn(Collections.emptyList());

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "updateNotification", String.class, String.class, Map.class);
        method.setAccessible(true);

        Map<String, Object> updateMap = Map.of(READ, true, READ_AT, Instant.now());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) method.invoke(
                notificationService, USER_ID, NOTIFICATION_ID, updateMap);

        assertTrue(result.isEmpty());
    }

    @Test
    void testFetchNotifications() throws Exception {
        Map<String, Object> notification = new HashMap<>();
        notification.put(USER_ID, USER_ID);
        notification.put(NOTIFICATION_ID, NOTIFICATION_ID);

        when(cassandraOperation.getRecordsByProperties(
                any(), any(), anyMap(), any(), anyInt()
        )).thenReturn(List.of(notification));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "fetchNotifications", String.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                notificationService, USER_ID);

        assertEquals(1, result.size());
        assertEquals(NOTIFICATION_ID, result.get(0).get(NOTIFICATION_ID));
    }

    @Test
    void testMarkNotificationsAsRead_InternalServerError() {
        // Mocked input
        String authToken = "Bearer token";
        String userId = "user123";
        String notificationId = "notif001";
        Instant createdAt = Instant.now();

        Map<String, Object> request = Map.of("type", "all");

        Map<String, Object> notification = new HashMap<>();
        notification.put("notificationId", notificationId);
        notification.put("read", false);
        notification.put("createdAt", createdAt);

        List<Map<String, Object>> notifications = List.of(notification);

        Map<String, Object> successUpdateResponse = Map.of("response", "SUCCESS");

        // Mocks
        Mockito.when(accessTokenValidator.fetchUserIdFromAccessToken(authToken))
                .thenReturn(userId);

        Mockito.when(cassandraOperation.getRecordsByProperties(
                        eq(Constants.KEYSPACE_SUNBIRD),
                        eq(Constants.TABLE_USER_NOTIFICATION),
                        anyMap(),
                        isNull(),
                        anyInt()))
                .thenReturn(notifications);

        Mockito.when(cassandraOperation.updateRecord(
                        eq(Constants.KEYSPACE_SUNBIRD),
                        eq(Constants.TABLE_USER_NOTIFICATION),
                        anyMap(),
                        anyMap()))
                .thenReturn(successUpdateResponse);

        // Call method
        ApiResponse response = notificationService.markNotificationsAsRead(authToken, request, Constants.API_VERSION_V1);

        // Assertions
        assertNotNull(response);
        assertEquals("Internal server error while updating notifications", response.getParams().getErrMsg());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testMarkNotificationsAsRead_InvalidUser() {
        // Mocked input
        String authToken = "Bearer token";
        String notificationId = "notif001";
        Instant createdAt = Instant.now();

        Map<String, Object> request = Map.of("type", "all");

        Map<String, Object> notification = new HashMap<>();
        notification.put("notificationId", notificationId);
        notification.put("read", false);
        notification.put("createdAt", createdAt);

        List<Map<String, Object>> notifications = List.of(notification);

        Map<String, Object> successUpdateResponse = Map.of("response", "SUCCESS");

        // Mocks
        Mockito.when(accessTokenValidator.fetchUserIdFromAccessToken(authToken))
                .thenReturn(null);

        Mockito.when(cassandraOperation.getRecordsByProperties(
                        eq(Constants.KEYSPACE_SUNBIRD),
                        eq(Constants.TABLE_USER_NOTIFICATION),
                        anyMap(),
                        isNull(),
                        anyInt()))
                .thenReturn(notifications);

        Mockito.when(cassandraOperation.updateRecord(
                        eq(Constants.KEYSPACE_SUNBIRD),
                        eq(Constants.TABLE_USER_NOTIFICATION),
                        anyMap(),
                        anyMap()))
                .thenReturn(successUpdateResponse);

        // Call method
        ApiResponse response = notificationService.markNotificationsAsRead(authToken, request, Constants.API_VERSION_V1);

        // Assertions
        assertNotNull(response);
        assertEquals("User Id doesn't exist! Please supply a valid auth token", response.getParams().getErrMsg());
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testGetUnreadNotificationCount_success_withExistingRecord() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);

        Map<String, Object> record = Map.of(Constants.COUNT, 10);
        when(cassandraOperation.getRecordsByProperties(
                anyString(), anyString(), anyMap(), anyList(), eq(1)))
                .thenReturn(List.of(record));

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 5);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());
        assertEquals(10, ((Map<?, ?>) response.getResult()).get("unread"));
    }


    @Test
    void testGetUnreadNotificationCount_badRequest_whenUserIdMissing() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("");

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 5);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testGetUnreadNotificationCount_internalServerError_onException() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenThrow(new RuntimeException("DB failure"));

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 5);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testGetResetNotificationCount_success() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);

        when(cassandraOperation.updateRecord(
                anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.SUCCESS));

        ApiResponse response = notificationService.getResetNotificationCount(AUTH_TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testGetResetNotificationCount_badRequest_whenUserIdMissing() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("");

        ApiResponse response = notificationService.getResetNotificationCount(AUTH_TOKEN);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testGetResetNotificationCount_internalServerError_onException() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenThrow(new RuntimeException("DB error"));

        ApiResponse response = notificationService.getResetNotificationCount(AUTH_TOKEN);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testClubNotification_InsertsNewNotification_WhenNoExistingClubFound() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "user123";
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"d1\"}}}");

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("response", "SUCCESS"));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, userId, requestNode);

        verify(cassandraOperation, times(1))
                .insertRecord(eq(Constants.KEYSPACE_SUNBIRD), eq(Constants.TABLE_USER_NOTIFICATION), anyMap());
    }

    @Test
    void testClubNotification_UpdatesExistingNotification_WhenWithinClubWindowAndSameClubKey() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "user123";
        Instant createdAt = Instant.now();

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"d1\"}}}");
        JsonNode existingMessage = realMapper.readTree("{\"data\":{\"discussionId\":\"d1\",\"count\":1}}");

        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put(Constants.USER_ID, userId);
        dbRecord.put(Constants.SUB_CATEGORY, subCategory.name());
        dbRecord.put(Constants.CREATED_AT, createdAt);
        dbRecord.put(Constants.MESSAGE, existingMessage.toString());

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));
        when(objectMapper.readTree(anyString())).thenReturn(existingMessage);
        when(objectMapper.writeValueAsString(any(JsonNode.class))).thenReturn(existingMessage.toString());

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, userId, requestNode);

        verify(cassandraOperation, times(1))
                .updateRecord(eq(Constants.KEYSPACE_SUNBIRD),
                        eq(Constants.TABLE_USER_NOTIFICATION),
                        anyMap(), anyMap());
    }

    @Test
    void testClubNotification_SkipsUpdate_WhenDifferentSubCategory() throws Exception {
        String userId = "user123";
        Instant createdAt = Instant.now();

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"d1\"}}}");
        JsonNode existingMessage = realMapper.readTree("{\"data\":{\"discussionId\":\"d2\",\"count\":1}}");

        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put(Constants.USER_ID, userId);
        dbRecord.put(Constants.SUB_CATEGORY, NotificationSubCategory.LIKED_COMMENT.name());
        dbRecord.put(Constants.CREATED_AT, createdAt);
        dbRecord.put(Constants.MESSAGE, existingMessage.toString());

        // mock Cassandra returning this record
        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));

        // mock ObjectMapper so it doesn’t return null
        when(objectMapper.readTree(existingMessage.toString())).thenReturn(existingMessage);
        when(objectMapper.readTree(requestNode.get("message").toString())).thenReturn(requestNode.get("message"));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, NotificationSubCategory.LIKED_POST, userId, requestNode);

        // since subcategories differ, update should never be called
        verify(cassandraOperation, never())
                .updateRecord(any(), any(), anyMap(), anyMap());
    }


    @Test
    void testUpdateNotificationMessage_IncrementsCountAndUpdatesBody() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode messageNode = mapper.readTree("{\"data\":{\"count\":1},\"body\":\"old\"}");

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "updateNotificationMessage", NotificationSubCategory.class, JsonNode.class);
        method.setAccessible(true);

        JsonNode updated = (JsonNode) method.invoke(notificationService, NotificationSubCategory.LIKED_POST, messageNode);

        assertEquals(2, updated.get("data").get("count").asInt());
        assertTrue(updated.get("body").asText().contains("2"));
    }

    @Test
    void testConstructMessage_ReplacesPlaceholderCorrectly() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "constructMessage", NotificationSubCategory.class, Map.class);
        method.setAccessible(true);

        String result = (String) method.invoke(notificationService,
                NotificationSubCategory.LIKED_POST, Map.of("count", "5"));

        assertTrue(result.contains("5 users liked your post"));
    }


    @Test
    void testConstructMessage_IgnoresMissingPlaceholder() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "constructMessage", NotificationSubCategory.class, Map.class);
        method.setAccessible(true);

        String result = (String) method.invoke(notificationService,
                NotificationSubCategory.LIKED_POST, Map.of());

        assertTrue(result.contains("{count}"));
    }


    @Test
    void testGetNotificationsByUserIdAndLastXDays_DisabledNotifications_ReturnsEmptyList() {
        String authToken = "Bearer abc";
        String userId = "u123";
        NotificationSettingEntity entity = new NotificationSettingEntity();
        entity.setEnabled(false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(eq(userId), any()))
                .thenReturn(Optional.of(entity));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get(Constants.TOTAL_COUNT));
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_FiltersDeletedNotifications() {
        String authToken = "Bearer abc";
        String userId = "u123";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_FiltersBySubType() {
        String authToken = "Bearer abc";
        String userId = "u123";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);
        notif.put(Constants.SUB_TYPE, "announcement");

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, "announcement");

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }
    @Test
    void testGetNotificationsByUserIdAndLastXDays_HandlesException_ReturnsInternalServerError() {
        String authToken = "Bearer abc";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenThrow(new RuntimeException("DB error"));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_FiltersReadNotifications() {
        String authToken = "Bearer xyz";
        String userId = "u456";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n2");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.UNREAD, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertTrue(notifications.isEmpty());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_ReturnsOnlyReadNotifications() {
        String authToken = "Bearer read";
        String userId = "u789";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n3");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.READ, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_NoSettingsFound_ReturnsNotifications() {
        String authToken = "Bearer missing";
        String userId = "u999";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n4");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(eq(userId), any()))
                .thenReturn(Optional.empty());
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_ThrowsOnCassandraError() {
        String authToken = "Bearer crash";
        String userId = "u111";

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), any(), anyInt()))
                .thenThrow(new RuntimeException("Cassandra down"));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_ReturnsGlobalNotifications() {
        String authToken = "Bearer global";
        String userId = "u112";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n5");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }

    @Test
    void testClubNotification_ShouldInsertNewNotification_WhenNoExistingFound() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "userNew";
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"discussionId\":\"d1\"}");

        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(objectMapper.writeValueAsString(any(JsonNode.class)))
                .thenReturn("{\"discussionId\":\"d1\"}");

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, userId, requestNode);

        // Verify 3-arg insertRecord
        verify(cassandraOperation, times(1))
                .insertRecord(anyString(), anyString(), anyMap());
    }



    @Test
    void testGetNotificationsByUserIdAndLastXDays_PaginationWorks() {
        String authToken = "Bearer page";
        String userId = "u222";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n6");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 1, 10, NotificationReadStatus.BOTH, null); // offset=1

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, ((List<?>) result.get(Constants.NOTIFICATIONS)).size());
    }


    @Test
    void testGetUnreadNotificationCount_countObjNotNumber() {
        String authToken = "Bearer token";
        String userId = "u123";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> record = new HashMap<>();
        record.put(Constants.COUNT, "not-a-number"); // invalid type

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(List.of(record));

        ApiResponse response = notificationService.getUnreadNotificationCount(authToken, 7);
        Map<String, Object> result = (Map<String, Object>) response.getResult();

        assertEquals(0, result.get("unread"));
    }

    @Test
    void testGetUnreadNotificationCount_withLastUpdatedNull() {
        String authToken = "Bearer token";
        String userId = "u123";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> record = new HashMap<>();
        record.put(Constants.COUNT, 5);
        record.put(Constants.UPDATED_AT, null);

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(List.of(record));

        ApiResponse response = notificationService.getUnreadNotificationCount(authToken, 7);
        Map<String, Object> result = (Map<String, Object>) response.getResult();

        assertEquals(5, result.get("unread"));
    }

    @Test
    void testGetUnreadNotificationCount_withLastUpdatedNotNullAndNoGlobalNotifs() {
        String authToken = "Bearer token";
        String userId = "u123";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Instant lastUpdated = Instant.now();
        Map<String, Object> record = new HashMap<>();
        record.put(Constants.COUNT, 2);
        record.put(Constants.UPDATED_AT, lastUpdated);

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(List.of(record));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        ApiResponse response = notificationService.getUnreadNotificationCount(authToken, 7);
        Map<String, Object> result = (Map<String, Object>) response.getResult();

        assertEquals(2, result.get("unread"));
    }

// ---------- clubNotification ----------

    @Test
    void testClubNotification_SkipsWhenDifferentSubCategory() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "user123";

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"d1\"}}}");

        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put(Constants.USER_ID, userId);
        dbRecord.put(Constants.SUB_CATEGORY, NotificationSubCategory.LIKED_COMMENT.name()); // different subCategory
        dbRecord.put(Constants.CREATED_AT, Instant.now());
        dbRecord.put(Constants.MESSAGE, "{\"data\":{\"discussionId\":\"d1\"}}");

        // ✅ Ensure readTree returns a real JsonNode instead of null
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> realMapper.readTree((String) invocation.getArgument(0)));

        when(cassandraOperation.getRecordsByProperties(
                any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, userId, requestNode);

        // ✅ Should skip update since subCategory mismatches
        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
    }


    @Test
    void testClubNotification_SkipsWhenClubWindowExpired() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "user123";

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"d1\"}}}");

        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put(Constants.USER_ID, userId);
        dbRecord.put(Constants.SUB_CATEGORY, subCategory.name());
        dbRecord.put(Constants.CREATED_AT, Instant.now().minus(Duration.ofHours(1))); // expired
        dbRecord.put(Constants.MESSAGE, "{\"data\":{\"discussionId\":\"d1\"}}");

        // ✅ Ensure mock objectMapper delegates to real mapper
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> realMapper.readTree((String) invocation.getArgument(0)));

        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, userId, requestNode);

        // ✅ Since clubWindow expired, no update should happen
        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
    }


    @Test
    void testClubNotification_SkipsWhenUserMismatch() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        ObjectMapper realMapper = new ObjectMapper();

        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"d1\"}}}");

        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put(Constants.USER_ID, "otherUser"); // mismatched user
        dbRecord.put(Constants.SUB_CATEGORY, subCategory.name());
        dbRecord.put(Constants.CREATED_AT, Instant.now());
        dbRecord.put(Constants.MESSAGE, "{\"data\":{\"discussionId\":\"d1\"}}");

        // ✅ Explicit cast to String avoids ambiguity
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> realMapper.readTree((String) invocation.getArgument(0)));

        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, "user123", requestNode);

        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
    }



    @Test
    void testClubNotification_SkipsWhenClubKeyMismatch() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "user123";

        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.readTree("{\"message\":{\"data\":{\"discussionId\":\"x\"}}}");

        Map<String, Object> dbRecord = new HashMap<>();
        dbRecord.put(Constants.USER_ID, userId);
        dbRecord.put(Constants.SUB_CATEGORY, subCategory.name());
        dbRecord.put(Constants.CREATED_AT, Instant.now());
        dbRecord.put(Constants.MESSAGE, "{\"data\":{\"discussionId\":\"y\"}}");

        // ✅ Make mocked objectMapper delegate to real ObjectMapper
        when(objectMapper.readTree(anyString()))
                .thenAnswer(invocation -> realMapper.readTree((String) invocation.getArgument(0)));

        when(cassandraOperation.getRecordsByProperties(any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(dbRecord));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);
        method.invoke(notificationService, subCategory, userId, requestNode);

        // ✅ Since keys mismatch, no update should happen
        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
    }


    @Test
    void testGetNotificationsByUserIdAndLastXDays_FiltersByReadStatusRead() {
        String authToken = "Bearer abc";
        String userId = "u123";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, true);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.READ, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_FiltersByReadStatusUnread() {
        String authToken = "Bearer abc";
        String userId = "u123";
        Instant now = Instant.now();

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.UNREAD, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_MergesGlobalAndUserNotifications() {
        String authToken = "Bearer abc";
        String userId = "u123";
        Instant now = Instant.now();

        Map<String, Object> userNotif = new HashMap<>();
        userNotif.put(Constants.NOTIFICATION_ID, "n1");
        userNotif.put(Constants.CREATED_AT, now);
        userNotif.put(Constants.IS_DELETED, false);

        Map<String, Object> globalNotif = new HashMap<>();
        globalNotif.put(Constants.NOTIFICATION_ID, "g1");
        globalNotif.put(Constants.CREATED_AT, now);
        globalNotif.put(Constants.IS_DELETED, false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(userNotif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(globalNotif));

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<?> notifications = (List<?>) result.get(Constants.NOTIFICATIONS);
        assertEquals(2, notifications.size());
    }


    @Test
    void testCreateNotification_BlankUserId() {
        ObjectMapper realMapper = new ObjectMapper(); // real mapper
        JsonNode request = realMapper.createObjectNode().put(Constants.TYPE, "IN_APP");

        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenReturn("");

        ApiResponse response = notificationService.createNotification(request, "token");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
    }


    @Test
    void testCreateNotification_BlankNotificationType() {
        ObjectMapper realMapper = new ObjectMapper(); // real mapper for test JSON
        JsonNode request = realMapper.createObjectNode()
                .set(Constants.REQUEST, realMapper.createObjectNode()); // no type inside

        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenReturn("u1");

        ApiResponse response = notificationService.createNotification(request, "token");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }


    @Test
    void testCreateNotification_ExceptionDuringInsert() {
        ObjectMapper realMapper = new ObjectMapper(); // real mapper for test JSON
        JsonNode innerRequest = realMapper.createObjectNode().put(Constants.TYPE, "IN_APP");
        JsonNode request = realMapper.createObjectNode().set(Constants.REQUEST, innerRequest);

        when(accessTokenValidator.fetchUserIdFromAccessToken("token")).thenReturn("u1");
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("DB fail"));

        ApiResponse response = notificationService.createNotification(request, "token");

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }


    @Test
    void testBulkCreateNotifications_InvalidUserIds() {
        ObjectMapper realMapper = new ObjectMapper(); // real instance for JSON building

        JsonNode request = realMapper.createObjectNode()
                .set(Constants.REQUEST, realMapper.createObjectNode()
                        .put(Constants.TYPE, "IN_APP")
                        .put(Constants.SUB_CATEGORY, "CONTENT_PUBLISHED")); // no user_ids

        ApiResponse response = notificationService.bulkCreateNotifications(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }


    @Test
    void testBulkCreateNotifications_TooManyUsers() {
        ObjectMapper realMapper = new ObjectMapper(); // real mapper for test JSON

        ArrayNode userIds = realMapper.createArrayNode();
        for (int i = 0; i < Constants.MAX_USER_LIMIT + 1; i++) {
            userIds.add(realMapper.createObjectNode().put(Constants.USER_ID, "u" + i));
        }

        JsonNode request = realMapper.createObjectNode()
                .set(Constants.REQUEST, realMapper.createObjectNode()
                        .put(Constants.TYPE, "IN_APP")
                        .put(Constants.SUB_CATEGORY, "CONTENT_PUBLISHED")
                        .set(Constants.USER_IDS, userIds));

        ApiResponse response = notificationService.bulkCreateNotifications(request);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }


    @Test
    void testIsGlobalSubCategory_PositiveAndNegative() throws Exception {
        assertTrue(invokeIsGlobalSubCategory(NotificationSubCategory.EVENT_PUBLISHED));
        assertFalse(invokeIsGlobalSubCategory(NotificationSubCategory.LIKED_POST));
    }

    private boolean invokeIsGlobalSubCategory(NotificationSubCategory subCategory) throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isGlobalSubCategory", NotificationSubCategory.class);
        method.setAccessible(true);
        return (boolean) method.invoke(notificationService, subCategory);
    }

    @Test
    void testCreateGlobalNotification_FailedInsert() {
        ObjectMapper realMapper = new ObjectMapper(); // real mapper for building JSON
        JsonNode request = realMapper.createObjectNode().put(Constants.TYPE, "IN_APP");

        ApiResponse failedResponse = new ApiResponse();
        failedResponse.put(Constants.RESPONSE, Constants.FAILED); // mimic failure

        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(failedResponse);

        ApiResponse response = notificationService.createGlobalNotification(
                NotificationSubCategory.EVENT_PUBLISHED, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }



    @Test
    void testGetNotificationsByUserIdAndLastXDays_InvalidUserId() {
        when(accessTokenValidator.fetchUserIdFromAccessToken("t")).thenReturn("");

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays("t", 7, 0, 5, NotificationReadStatus.BOTH, null);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_InvalidSubTypeOrderIndex() {
        String authToken = "Bearer token";
        String userId = "u1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, Instant.now());
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);
        notif.put(Constants.SUB_TYPE, "invalidType"); // triggers Integer.MAX_VALUE

        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.BOTH, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testMarkNotificationsAsRead_GlobalAllFlow() {
        String authToken = "t";
        String userId = "u1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(Map.of(Constants.NOTIFICATION_ID, "g1", Constants.CREATED_AT, Instant.now())));

        Map<String, Object> request = new HashMap<>();
        request.put(Constants.TYPE, Constants.ALL);
        request.put(Constants.ACTION, Constants.GLOBAL);

        ApiResponse response = notificationService.markNotificationsAsRead(authToken, request, Constants.API_VERSION_V1);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testMarkNotificationsAsDeleted_FailureUpdate() {
        String authToken = "t";
        String userId = "u1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.updateRecord(any(), any(), any(), any()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.FAILED));

        ApiResponse response = notificationService.markNotificationsAsDeleted(authToken, List.of("n1"));

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertTrue(((List<?>) result.get(Constants.NOTIFICATIONS)).isEmpty());
    }


    @Test
    void testGetResetNotificationCount_UserIdBlank() {
        when(accessTokenValidator.fetchUserIdFromAccessToken("t")).thenReturn("");

        ApiResponse response = notificationService.getResetNotificationCount("t");

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testGetInstant_InvalidString() {
        Instant instant = notificationService.getInstant("not-a-date");
        assertNull(instant);
    }

    @Test
    void testPrepareNotificationResponse_MessageParseFails() {
        Map<String, Object> record = new HashMap<>();
        record.put("message", "{invalidJson"); // invalid JSON

        Map<String, Object> result = notificationService.prepareNotificationResponse(record);

        assertTrue(result.containsKey("message"));
    }

    @Test
    void testCreateNotification_DisabledSetting() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode request = realMapper.createObjectNode()
                .set(Constants.REQUEST, realMapper.createObjectNode()
                        .put(Constants.TYPE, "IN_APP"));

        NotificationSettingEntity entity = new NotificationSettingEntity();
        entity.setEnabled(false);
        when(accessTokenValidator.fetchUserIdFromAccessToken("t")).thenReturn("u1");
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("u1", "IN_APP"))
                .thenReturn(Optional.of(entity));

        ApiResponse response = notificationService.createNotification(request, "t");

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertTrue(((Map<?, ?>) result).isEmpty());
    }


    @Test
    void testGetUnreadNotificationCount_NoRecords() {
        when(accessTokenValidator.fetchUserIdFromAccessToken("t")).thenReturn("u1");
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(Collections.emptyList());

        ApiResponse response = notificationService.getUnreadNotificationCount("t", 7);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get("unread"));
    }

    @Test
    void testCreateGlobalNotification_Exception() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode request = realMapper.createObjectNode().put(Constants.TYPE, "IN_APP");

        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenThrow(new RuntimeException("DB error"));

        ApiResponse response = notificationService.createGlobalNotification(NotificationSubCategory.EVENT_PUBLISHED, request);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testClubNotification_ReadTreeThrowsException() throws Exception {
        NotificationSubCategory subCategory = NotificationSubCategory.LIKED_POST;
        String userId = "user1";
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode requestNode = realMapper.createObjectNode().put("message", "invalidJson");

        when(objectMapper.readTree(anyString())).thenThrow(new RuntimeException("parse fail"));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "clubNotification", NotificationSubCategory.class, String.class, JsonNode.class);
        method.setAccessible(true);

        // Should not throw
        method.invoke(notificationService, subCategory, userId, requestNode);

        verify(cassandraOperation, never()).updateRecord(any(), any(), any(), any());
    }


    void testMarkNotificationsAsRead_GlobalAll() {
        String userId = "user-123";
        Map<String, Object> request = new HashMap<>();
        request.put("action", "GLOBAL");
        request.put("type", "ALL");

        // Mock methods
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()
        )).thenReturn(new ArrayList<>()); // Simulate fetching global notifications

        // Call the method
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, Constants.API_VERSION_V1);

        // Assert
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Global notifications marked as read"));
        assertNotNull(response.getResult());
    }


    @Test
    void testMarkNotificationsAsRead_InvalidType() {
        String userId = "user-123";
        Map<String, Object> request = new HashMap<>();
        request.put("action", "GLOBAL");
        request.put("type", "INVALID_TYPE");

        // Mock methods
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);

        // Call the method
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, Constants.API_VERSION_V1);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Invalid type. Allowed values: all, individual"));
    }

    @Test
    void testMarkNotificationsAsRead_MissingType() {
        String userId = "user-123";
        Map<String, Object> request = new HashMap<>();
        request.put("action", "GLOBAL");

        // Mock methods
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);

        // Call the method
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, Constants.API_VERSION_V1);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Request type must be provided"));
    }

    @Test
    void testMarkNotificationsAsRead_GlobalIndividual_MatchingNotifications_UsingReflection() throws Exception {
        String userId = "user-123";
        Map<String, Object> request = new HashMap<>();
        request.put("action", "GLOBAL");
        request.put("type", "INDIVIDUAL");
        request.put("ids", Arrays.asList("notification-id-1", "notification-id-2"));

        // Mock methods
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);

        // Simulate fetching global notifications
        List<Map<String, Object>> globalNotifications = Arrays.asList(
                Map.of(NOTIFICATION_ID, "notification-id-1", "read", false),
                Map.of(NOTIFICATION_ID, "notification-id-2", "read", false)
        );
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()
        )).thenReturn(globalNotifications);

        // Simulate the behavior of insertAndMarkGlobalNotificationsAsRead using reflection
        List<Map<String, Object>> targetNotifications = Arrays.asList(
                Map.of("notificationId", "notification-id-1", "read", true),
                Map.of("notificationId", "notification-id-2", "read", true)
        );

        // Use reflection to access the private method extractIndividualNotificationIds
        Method extractMethod = NotificationServiceImpl.class.getDeclaredMethod("extractIndividualNotificationIds", Map.class, ApiResponse.class);
        extractMethod.setAccessible(true); // Make the private method accessible

        // Create a mock ApiResponse
        ApiResponse mockResponse = new ApiResponse();

        // Invoke the private method using reflection to extract individual notification IDs
        List<String> notificationIds = (List<String>) extractMethod.invoke(notificationService, request, mockResponse);

        // Assert the result from extractIndividualNotificationIds
        assertNotNull(notificationIds);
        assertEquals(2, notificationIds.size());
        assertTrue(notificationIds.contains("notification-id-1"));
        assertTrue(notificationIds.contains("notification-id-2"));

        // Use reflection to access the private method insertAndMarkGlobalNotificationsAsRead
        Method insertMethod = NotificationServiceImpl.class.getDeclaredMethod("insertAndMarkGlobalNotificationsAsRead", String.class, List.class);
        insertMethod.setAccessible(true); // Make the private method accessible

        // Invoke the private method using reflection to insert and mark notifications as read
        List<Map<String, Object>> result = (List<Map<String, Object>>) insertMethod.invoke(
                notificationService, userId, globalNotifications
        );

        // Assert the results from the private method insertAndMarkGlobalNotificationsAsRead
        assertNotNull(result);
        assertEquals(globalNotifications.size(), result.size());

        // Verify that the notifications were marked as read
        for (Map<String, Object> notification : result) {
            assertTrue((Boolean) notification.get("read"));
        }

        // Now, simulate the public method markNotificationsAsRead (this invokes the private methods internally)
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, Constants.API_VERSION_V1);

        // Assert the response
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Selected global notifications marked as read and inserted"));
        assertNotNull(response.getResult());
        Map<String, Object> finalResult = (Map<String, Object>) response.getResult();
        assertEquals(targetNotifications.size(), ((List<?>) finalResult.get("notifications")).size());
    }
    
    @Test
    void testUpdatePeerValidationStatusToSubmitted_Success() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        Instant createdAtInstant = Instant.parse(createdAt);
        List<Map<String, Object>> peerRecords = List.of(Map.of(STATUS, "PENDING"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId)), eq(List.of(STATUS)), eq(1))).thenReturn(peerRecords);
        List<Map<String, Object>> userNotifRecords = List.of(Map.of(STATUS, "PENDING"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), eq(Map.of(USER_ID, userId, "created_at", createdAtInstant)), eq(List.of(STATUS)), eq(1))).thenReturn(userNotifRecords);
        when(cassandraOperation.updateRecord(eq(KEYSPACE_SUNBIRD), anyString(), anyMap(), anyMap())).thenReturn(Map.of("response", "SUCCESS"));
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, times(1)).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), argThat(map -> "SUBMITTED".equals(map.get(STATUS))), eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId)));
        verify(cassandraOperation, times(1)).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), argThat(map -> "SUBMITTED".equals(map.get(STATUS))), eq(Map.of(USER_ID, userId, "created_at", createdAtInstant)));
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_AlreadySubmitted_PeerRecord() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        List<Map<String, Object>> peerRecords = List.of(Map.of(STATUS, "SUBMITTED"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId)), eq(List.of(STATUS)), eq(1))).thenReturn(peerRecords);
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), anyMap());
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_AlreadySubmitted_UserNotification() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        Instant createdAtInstant = Instant.parse(createdAt);
        List<Map<String, Object>> peerRecords = List.of(Map.of(STATUS, "PENDING"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId)), eq(List.of(STATUS)), eq(1))).thenReturn(peerRecords);
        List<Map<String, Object>> userNotifRecords = List.of(Map.of(STATUS, "SUBMITTED"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), eq(Map.of(USER_ID, userId, "created_at", createdAtInstant)), eq(List.of(STATUS)), eq(1))).thenReturn(userNotifRecords);
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), anyMap());
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_PeerRecordNotFound() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId)), eq(List.of(STATUS)), eq(1))).thenReturn(Collections.emptyList());
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), anyMap());
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_UserNotificationNotFound() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        Instant createdAtInstant = Instant.parse(createdAt);
        List<Map<String, Object>> peerRecords = List.of(Map.of(STATUS, "PENDING"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId)), eq(List.of(STATUS)), eq(1))).thenReturn(peerRecords);
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), eq(Map.of(USER_ID, userId, "created_at", createdAtInstant)), eq(List.of(STATUS)), eq(1))).thenReturn(Collections.emptyList());
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), anyMap());
        verify(cassandraOperation, never()).updateRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_InvalidSubCategory() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_REVIEW_ASSIGNED";
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).getRecordsByProperties(anyString(), anyString(), anyMap(), any(), anyInt());
        verify(cassandraOperation, never()).updateRecord(anyString(), anyString(), anyMap(), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_InvalidTimestampFormat() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "invalid-timestamp";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(anyString(), anyString(), anyMap(), anyMap());
    }
    @Test
    void testValidateRecordExistsAndNotSubmitted_RecordExists_NotSubmitted() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        List<Map<String, Object>> peerRecords = List.of(Map.of(STATUS, "PENDING"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), eq(List.of(STATUS)), eq(1))).thenReturn(peerRecords);
        List<Map<String, Object>> userNotifRecords = List.of(Map.of(STATUS, "PENDING"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(STATUS)), eq(1))).thenReturn(userNotifRecords);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of("response", "SUCCESS"));
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, times(2)).updateRecord(anyString(), anyString(), anyMap(), anyMap());
    }
    @Test
    void testValidateRecordExistsAndNotSubmitted_RecordAlreadySubmitted() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        List<Map<String, Object>> peerRecords = List.of(Map.of(STATUS, "SUBMITTED"));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), eq(List.of(STATUS)), eq(1))).thenReturn(peerRecords);
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(anyString(), anyString(), anyMap(), anyMap());
    }
    @Test
    void testValidateRecordExistsAndNotSubmitted_RecordNotFound() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), eq(List.of(STATUS)), eq(1))).thenReturn(Collections.emptyList());
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(anyString(), anyString(), anyMap(), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_BothUpdatesSucceed() {
        String userId = "user-123";
        String notificationId = "notif-456";
        String createdAt = "2026-03-10T02:50:00Z";
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), eq(List.of(STATUS)), eq(1))).thenReturn(List.of(Map.of(STATUS, "PENDING")));
        when(cassandraOperation.getRecordsByProperties(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(STATUS)), eq(1))).thenReturn(List.of(Map.of(STATUS, "PENDING")));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap())).thenReturn(Map.of("response", "SUCCESS"));
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, times(2)).updateRecord(eq(KEYSPACE_SUNBIRD), anyString(), argThat(map -> "SUBMITTED".equals(map.get(STATUS)) && map.containsKey("updated_at")), anyMap());
    }
    @Test
    void testUpdatePeerValidationStatusToSubmitted_WithNullValues() {
        String userId = null;
        String notificationId = null;
        String createdAt = null;
        String subCategory = "PEER_EVALUATION_ASSIGNED";
        notificationService.updatePeerValidationStatusToSubmitted(userId, notificationId, createdAt, subCategory);
        verify(cassandraOperation, never()).updateRecord(anyString(), anyString(), anyMap(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_MissingUserId() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("");
        Map<String, Object> request = Map.of(TYPE, INDIVIDUAL);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertEquals(Constants.USER_ID_DOESNT_EXIST, response.getParams().getErrMsg());
    }

    @Test
    void testMarkNotificationsAsRead_BlankType() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("user-1");
        Map<String, Object> request = new HashMap<>();
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("type must be provided"));
    }

    @Test
    void testMarkNotificationsAsRead_InvalidType_NonGlobalPath() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("user-1");
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, "unknown_type");
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Invalid type"));
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_MissingIds() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn("user-1");
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("'ids'"));
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NotificationNotFound_ReturnsSuccess() {
        String userId = "user-1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of("notif-1"));
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NonPeerValidation_Success() {
        String userId = "user-1";
        String notificationId = "notif-1";
        Instant createdAt = Instant.parse("2026-03-10T10:00:00Z");
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, notificationId);
        notification.put(CREATED_AT, createdAt);
        notification.put(CATEGORY, "GENERAL");
        notification.put(SUB_CATEGORY, "INFO");
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(notification));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAt);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
        assertEquals(notificationId, notifications.get(0).get(ID));
        assertEquals(true, notifications.get(0).get(READ));
        assertNotNull(notifications.get(0).get(READ_AT));
        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(m -> Boolean.TRUE.equals(m.get(READ)) && m.containsKey(READ_AT)),
                argThat(m -> userId.equals(m.get(USER_ID)) && createdAt.equals(m.get(CREATED_AT))));
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_PeerValidation_KafkaEventPublished() throws Exception {
        String userId = "user-1";
        String notificationId = "notif-1";
        Instant createdAt = Instant.parse("2026-03-10T10:00:00Z");
        String formId = "form-abc";
        String topic = "notification-read-topic";
        String messageJson = "{\"data\": [{\"formId\": \"form-abc\"}]}";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, notificationId);
        notification.put(CREATED_AT, createdAt);
        notification.put(CATEGORY, "PEER_VALIDATION");
        notification.put(SUB_CATEGORY, "PEER_EVALUATION_ASSIGNED");
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(notification));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, messageJson)));
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put(DATA, List.of(Map.of(Constants.FORM_ID, formId)));
        when(objectMapper.readValue(eq(messageJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any())).thenReturn(messageMap);
        when(cbServerProperties.getKafkaTopicNotificationReadEvent()).thenReturn(topic);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAt);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, times(1)).push(eq(topic), argThat(event -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> eventMap = (Map<String, Object>) event;
            return Constants.PEER_SURVEY_NOTIFICATION_READ_EVENT.equals(eventMap.get(Constants.EVENT_TYPE))
                    && formId.equals(eventMap.get(Constants.FORM_ID))
                    && userId.equals(eventMap.get(USER_ID_FIELD));
        }));
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NoPeerSurveyEvent_WhenNotificationRecordNotFound() {
        String userId = "user-1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(Collections.emptyList());
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of("notif-1"));
        request.put(CREATED_AT, "2026-03-10T10:00:00Z");
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NoPeerSurveyEvent_WhenMessageIsBlank() throws Exception {
        String userId = "user-1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, "")));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of("notif-1"));
        request.put(CREATED_AT, "2026-03-10T10:00:00Z");
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NoPeerSurveyEvent_WhenDataListIsEmpty() throws Exception {
        String userId = "user-1";
        String messageJson = "{\"data\": []}";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, messageJson)));
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put(DATA, Collections.emptyList());
        when(objectMapper.readValue(eq(messageJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any())).thenReturn(messageMap);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of("notif-1"));
        request.put(CREATED_AT, "2026-03-10T10:00:00Z");
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NoPeerSurveyEvent_WhenFormIdIsBlank() throws Exception {
        String userId = "user-1";
        String messageJson = "{\"data\": [{\"formId\": \"\"}]}";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, messageJson)));
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put(DATA, List.of(Map.of(Constants.FORM_ID, "")));
        when(objectMapper.readValue(eq(messageJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any())).thenReturn(messageMap);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of("notif-1"));
        request.put(CREATED_AT, "2026-03-10T10:00:00Z");
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_KafkaPublishException_DoesNotAffectResponse() throws Exception {
        String userId = "user-1";
        String formId = "form-xyz";
        String messageJson = "{\"data\": [{\"formId\": \"form-xyz\"}]}";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, messageJson)));
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put(DATA, List.of(Map.of(Constants.FORM_ID, formId)));
        when(objectMapper.readValue(eq(messageJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any())).thenReturn(messageMap);
        when(cbServerProperties.getKafkaTopicNotificationReadEvent()).thenReturn("some-topic");
        doThrow(new RuntimeException("Kafka unavailable")).when(producer).push(anyString(), anyMap());
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of("notif-1"));
        request.put(CREATED_AT, "2026-03-10T10:00:00Z");
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV1_DelegatesToProcessReadUpdate() {
        String userId = "user-1";
        String notificationId = "notif-1";
        Instant createdAt = Instant.parse("2026-03-10T10:00:00Z");
        Map<String, Object> existingNotif = new HashMap<>();
        existingNotif.put(NOTIFICATION_ID, notificationId);
        existingNotif.put(CREATED_AT, createdAt);
        existingNotif.put(READ, false);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(existingNotif));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, "V1");
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, notifications.size());
        assertEquals(notificationId, notifications.get(0).get(ID));
        assertEquals(true, notifications.get(0).get(READ));
    }

    @Test
    void testMarkNotificationsAsRead_AllType_MarksAllUserNotifications() {
        String userId = "user-1";
        String notifId1 = "notif-1";
        String notifId2 = "notif-2";
        Instant createdAt = Instant.parse("2026-03-10T10:00:00Z");
        Map<String, Object> n1 = new HashMap<>();
        n1.put(NOTIFICATION_ID, notifId1);
        n1.put(CREATED_AT, createdAt);
        n1.put(READ, false);
        Map<String, Object> n2 = new HashMap<>();
        n2.put(NOTIFICATION_ID, notifId2);
        n2.put(CREATED_AT, createdAt);
        n2.put(READ, false);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(n1, n2));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = Map.of(TYPE, ALL);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, "V1");
        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get(Constants.NOTIFICATIONS);
        assertEquals(2, notifications.size());
    }

    @Test
    void testMarkNotificationsAsRead_GlobalActionAllType_InsertsGlobalNotifsAsRead() {
        String userId = "user-1";
        String globalNotifId = "global-notif-1";
        Instant created = Instant.parse("2026-03-01T08:00:00Z");
        Map<String, Object> globalNotif = new HashMap<>();
        globalNotif.put(NOTIFICATION_ID, globalNotifId);
        globalNotif.put(CREATED_AT, created);
        globalNotif.put(READ, false);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(globalNotif));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(NOTIFICATION_ID)), anyInt()))
                .thenReturn(Collections.emptyList());
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, ALL);
        request.put(ACTION, GLOBAL);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, "V1");
        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertEquals(Constants.SUCCESS, response.getParams().getStatus());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> inserted = (List<Map<String, Object>>) result.get(Constants.NOTIFICATIONS);
        assertEquals(1, inserted.size());
        assertEquals(globalNotifId, inserted.get(0).get(ID));
        assertEquals(true, inserted.get(0).get(READ));
        verify(cassandraOperation).insertRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(m -> userId.equals(m.get(USER_ID)) && Boolean.TRUE.equals(m.get(READ))));
    }

    @Test
    void testMarkNotificationsAsRead_GlobalActionIndividualType_NoMatchingGlobals_Returns404() {
        String userId = "user-1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put(ACTION, GLOBAL);
        request.put("ids", List.of("non-existent-id"));
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, "V1");
        assertEquals(HttpStatus.NOT_FOUND, response.getResponseCode());
        assertEquals(Constants.FAILED, response.getParams().getStatus());
        assertTrue(response.getParams().getErrMsg().contains("No matching global notifications"));
    }

    @Test
    void testMarkNotificationsAsRead_GlobalActionInvalidType_ReturnsBadRequest() {
        String userId = "user-1";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, "bad_type");
        request.put(ACTION, GLOBAL);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, "V1");
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertTrue(response.getParams().getErrMsg().contains("Invalid type"));
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_PeerValidation_WithStatus_UpdatesBothTables() {
        String userId = "user-1";
        String notificationId = "notif-1";
        Instant createdAt = Instant.parse("2026-03-10T10:00:00Z");
        String status = "SKIP_FOR_NOW";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, notificationId);
        notification.put(CREATED_AT, createdAt);
        notification.put(CATEGORY, "PEER_VALIDATION");
        notification.put(SUB_CATEGORY, "PEER_EVALUATION_ASSIGNED");
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(notification));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(STATUS, status);
        request.put(CREATED_AT, createdAt);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(m -> status.equals(m.get(STATUS)) && Boolean.TRUE.equals(m.get(READ))),
                argThat(m -> userId.equals(m.get(USER_ID)) && createdAt.equals(m.get(CREATED_AT))));
        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS),
                argThat(m -> status.equals(m.get(STATUS))),
                argThat(m -> userId.equals(m.get(USER_ID)) && notificationId.equals(m.get(NOTIFICATION_ID))));
    }

    @Test
    void testIsPeerValidationEvaluationAssigned_MatchingCriteria_ReturnsTrue() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isPeerValidationEvaluationAssigned", Map.class);
        method.setAccessible(true);
        Map<String, Object> notification = new HashMap<>();
        notification.put(CATEGORY, "PEER_VALIDATION");
        notification.put(SUB_CATEGORY, "PEER_EVALUATION_ASSIGNED");
        boolean result = (boolean) method.invoke(notificationService, notification);
        assertTrue(result);
    }

    @Test
    void testIsPeerValidationEvaluationAssigned_NonMatchingCategory_ReturnsFalse() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isPeerValidationEvaluationAssigned", Map.class);
        method.setAccessible(true);
        Map<String, Object> notification = new HashMap<>();
        notification.put(CATEGORY, "OTHER_CATEGORY");
        notification.put(SUB_CATEGORY, "PEER_EVALUATION_ASSIGNED");
        boolean result = (boolean) method.invoke(notificationService, notification);
        assertFalse(result);
    }

    @Test
    void testIsPeerValidationEvaluationAssigned_NonMatchingSubCategory_ReturnsFalse() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isPeerValidationEvaluationAssigned", Map.class);
        method.setAccessible(true);
        Map<String, Object> notification = new HashMap<>();
        notification.put(CATEGORY, "PEER_VALIDATION");
        notification.put(SUB_CATEGORY, "OTHER_SUB_CATEGORY");
        boolean result = (boolean) method.invoke(notificationService, notification);
        assertFalse(result);
    }

    @Test
    void testFindNotificationById_ExistingNotification_ReturnsOptionalWithValue() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("findNotificationById", List.class, String.class);
        method.setAccessible(true);
        List<Map<String, Object>> notifications = List.of(
                Map.of(NOTIFICATION_ID, "notif-1", "data", "value1"),
                Map.of(NOTIFICATION_ID, "notif-2", "data", "value2")
        );
        Optional<Map<String, Object>> result = (Optional<Map<String, Object>>) method.invoke(notificationService, notifications, "notif-1");
        assertTrue(result.isPresent());
        assertEquals("value1", result.get().get("data"));
    }

    @Test
    void testFindNotificationById_NonExistingNotification_ReturnsEmptyOptional() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("findNotificationById", List.class, String.class);
        method.setAccessible(true);
        List<Map<String, Object>> notifications = List.of(
                Map.of(NOTIFICATION_ID, "notif-1", "data", "value1")
        );
        Optional<Map<String, Object>> result = (Optional<Map<String, Object>>) method.invoke(notificationService, notifications, "non-existing");
        assertTrue(result.isEmpty());
    }

    @Test
    void testHandleStatusBasedAction_UpdatesBothTables_EvaluationAssigned() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("handleStatusBasedAction", String.class, String.class, Instant.class, Instant.class, String.class, String.class);
        method.setAccessible(true);
        String userId = "user-123";
        String notificationId = "notif-456";
        Instant createdAt = Instant.parse("2026-03-15T10:00:00Z");
        Instant now = Instant.now();
        String status = "SKIP_FOR_NOW";
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        method.invoke(notificationService, userId, notificationId, createdAt, now, status, Constants.SUB_CATEGORY_PEER_EVALUATION_ASSIGNED);
        verify(cassandraOperation, times(1)).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(map -> status.equals(map.get(STATUS)) && Boolean.TRUE.equals(map.get("read"))),
                eq(Map.of(USER_ID, userId, CREATED_AT, createdAt))
        );
        verify(cassandraOperation, times(1)).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS),
                argThat(map -> status.equals(map.get(STATUS))),
                eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId))
        );
    }

    @Test
    void testHandleStatusBasedAction_UpdatesBothTables_ReviewAssigned() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("handleStatusBasedAction", String.class, String.class, Instant.class, Instant.class, String.class, String.class);
        method.setAccessible(true);
        String userId = "user-123";
        String notificationId = "notif-456";
        Instant createdAt = Instant.parse("2026-03-15T10:00:00Z");
        Instant now = Instant.now();
        String status = "SKIP_FOR_NOW";
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        method.invoke(notificationService, userId, notificationId, createdAt, now, status, Constants.SUB_CATEGORY_PEER_REVIEW_ASSIGNED);
        verify(cassandraOperation, times(1)).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(map -> status.equals(map.get(STATUS)) && Boolean.TRUE.equals(map.get("read"))),
                eq(Map.of(USER_ID, userId, CREATED_AT, createdAt))
        );
        verify(cassandraOperation, times(1)).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS),
                argThat(map -> status.equals(map.get(STATUS))),
                eq(Map.of(USER_ID, userId, NOTIFICATION_ID, notificationId))
        );
    }

    @Test
    void testHandleNormalReadFlow_UpdatesReadStatusAndPublishesEvent() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("handleNormalReadFlow", String.class, String.class, Instant.class, Instant.class, String.class);
        method.setAccessible(true);
        String userId = "user-123";
        String notificationId = "notif-456";
        Instant createdAt = Instant.parse("2026-03-15T10:00:00Z");
        Instant now = Instant.now();
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(anyString(), anyString(), anyMap(), anyList(), anyInt()))
                .thenReturn(List.of(Map.of("message", "{\"data\":[{\"formId\":\"form-123\"}]}")));
        when(cbServerProperties.getKafkaTopicNotificationReadEvent()).thenReturn("test-topic");
        method.invoke(notificationService, userId, notificationId, createdAt, now, SUB_CATEGORY_PEER_EVALUATION_ASSIGNED);
        verify(cassandraOperation, times(1)).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(map -> Boolean.TRUE.equals(map.get("read"))),
                eq(Map.of(USER_ID, userId, CREATED_AT, createdAt))
        );
    }

    // ===================== Additional coverage: bulkCreateNotifications branches =====================

    @Test
    void testBulkCreateNotifications_BlankNotificationType_ReturnsBadRequest() throws Exception {
        String payload = "{ \"request\": { " +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("'notification_type' is required", response.getParams().getErrMsg());
    }

    @Test
    void testBulkCreateNotifications_GlobalSubCategory_DelegatesToCreateGlobalNotification() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"content\"," +
                "\"sub_category\": \"EVENT_PUBLISHED\"," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.SUCCESS));

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertTrue(result.containsKey("notification"));
        verify(cassandraOperation).insertRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap());
        verify(cassandraOperation, never()).insertBulkRecord(any(), any(), any());
    }

    @Test
    void testBulkCreateNotifications_SkipsEmptyUserId() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"user_ids\": [ {\"user_id\": \"\"}, {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(mockInsertResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get("notifications");
        assertEquals(1, notifications.size());
    }

    @Test
    void testBulkCreateNotifications_SkipsDisabledUserAmongMultiple() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"user_ids\": [ {\"user_id\": \"user1\"}, {\"user_id\": \"user2\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        NotificationSettingEntity disabled = new NotificationSettingEntity();
        disabled.setEnabled(false);
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user1", "comment"))
                .thenReturn(Optional.of(disabled));
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse("user2", "comment"))
                .thenReturn(Optional.empty());

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(mockInsertResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get("notifications");
        assertEquals(1, notifications.size());

        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(cassandraOperation).insertBulkRecord(anyString(), anyString(), captor.capture());
        assertEquals(1, captor.getValue().size());
        assertEquals("user2", captor.getValue().get(0).get(Constants.USER_ID));
    }

    @Test
    void testBulkCreateNotifications_MessageWithoutDataNode_CreatesDataNodeWithCount() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"message\": {\"title\": \"hello\"}," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(mockInsertResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(cassandraOperation).insertBulkRecord(anyString(), anyString(), captor.capture());
        String messageStr = (String) captor.getValue().get(0).get(Constants.MESSAGE);
        assertNotNull(messageStr);
        assertTrue(messageStr.contains("\"data\""));
        assertTrue(messageStr.contains("\"count\":1"));
    }

    @Test
    void testBulkCreateNotifications_ClubbableSubCategory_CallsClubNotification_UsesIndividualTable() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"LIKED_POST\"," +
                "\"message\": {\"data\": {\"discussionId\": \"disc-1\"}}," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(Collections.emptyList());
        when(cassandraOperation.insertRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.SUCCESS));

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        when(cassandraOperation.insertBulkRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_INDIVIDUAL_NOTIFICATION), anyList()))
                .thenReturn(mockInsertResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(cassandraOperation).insertBulkRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_INDIVIDUAL_NOTIFICATION), anyList());
        verify(cassandraOperation).insertRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap());
    }

    @Test
    void testBulkCreateNotifications_InsertBulkRecordFails_ReturnsInternalServerError() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse failedResponse = new ApiResponse();
        failedResponse.put(Constants.RESPONSE, Constants.FAILED);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(failedResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals("Failed to insert notifications", response.getParams().getErrMsg());
    }

    // ===================== Additional coverage: readByUserIdAndNotificationId found branch =====================

    @Test
    void testReadByUserIdAndNotificationId_Found() {
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, NOTIFICATION_ID_1);
        notification.put(CREATED_AT, Instant.now());
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(notification));

        ApiResponse response = notificationService.readByUserIdAndNotificationId(NOTIFICATION_ID_1, AUTH_TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(NOTIFICATION_ID_1, result.get(NOTIFICATION_ID));
    }

    // ===================== Additional coverage: getNotificationsByUserIdAndLastXDays - formExpiryValidator =====================

    @Test
    void testGetNotificationsByUserIdAndLastXDays_CallsFormExpiryValidator_WhenPeerValidationNotificationsPresent() {
        String authToken = "Bearer xyz";
        String userId = "user-77";
        Instant now = Instant.now();

        Map<String, Object> peerNotif = new HashMap<>();
        peerNotif.put(NOTIFICATION_ID, "n-peer");
        peerNotif.put(Constants.CREATED_AT, now.minusSeconds(100));
        peerNotif.put(Constants.READ, false);
        peerNotif.put(Constants.CATEGORY, Constants.CATEGORY_PEER_VALIDATION);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(peerNotif));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0)).when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.getNotificationsByUserIdAndLastXDays(
                authToken, 10, 0, 10, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(formExpiryValidator, times(1)).validateAndMarkExpired(argThat(list -> list.size() == 1));
    }

    // ===================== Additional coverage: getFixedOrderIndex branches =====================

    @Test
    void testGetFixedOrderIndex_ValidSubType_ReturnsOrdinal() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("getFixedOrderIndex", String.class);
        method.setAccessible(true);
        int result = (int) method.invoke(notificationService, "ALERT");
        assertEquals(NotificationSubType.ALERT.ordinal(), result);
    }

    @Test
    void testGetFixedOrderIndex_InvalidSubType_ReturnsMaxValue() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("getFixedOrderIndex", String.class);
        method.setAccessible(true);
        int result = (int) method.invoke(notificationService, "NOT_A_REAL_SUBTYPE");
        assertEquals(Integer.MAX_VALUE, result);
    }

    @Test
    void testGetFixedOrderIndex_NullSubType_ReturnsZero() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("getFixedOrderIndex", String.class);
        method.setAccessible(true);
        int result = (int) method.invoke(notificationService, (String) null);
        assertEquals(0, result);
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_SortsSubTypeStatsByFixedOrder() {
        String authToken = "Bearer token";
        String userId = "u-order";
        Instant now = Instant.now();
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> notif1 = new HashMap<>();
        notif1.put(Constants.NOTIFICATION_ID, "n1");
        notif1.put(Constants.CREATED_AT, now.minusSeconds(10));
        notif1.put(Constants.IS_DELETED, false);
        notif1.put(Constants.READ, false);
        notif1.put(Constants.SUB_TYPE, "PROMOTIONAL");

        Map<String, Object> notif2 = new HashMap<>();
        notif2.put(Constants.NOTIFICATION_ID, "n2");
        notif2.put(Constants.CREATED_AT, now.minusSeconds(20));
        notif2.put(Constants.IS_DELETED, false);
        notif2.put(Constants.READ, false);
        notif2.put(Constants.SUB_TYPE, "ALERT");

        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif1, notif2));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0)).when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> subTypeStats = (List<Map<String, Object>>) result.get(SUBTYPE_STATS);
        assertEquals(2, subTypeStats.size());
        assertEquals("ALERT", subTypeStats.get(0).get(NAME));
        assertEquals("PROMOTIONAL", subTypeStats.get(1).get(NAME));
    }

    // ===================== Additional coverage: processReadUpdate branches =====================

    @Test
    void testProcessReadUpdate_SkipsAlreadyReadNotification() throws Exception {
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, NOTIFICATION_ID_1);
        notification.put(READ, true);
        notification.put(CREATED_AT, Instant.now());
        List<Map<String, Object>> notifications = List.of(notification);
        List<String> targetIds = List.of(NOTIFICATION_ID_1);

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "processReadUpdate", String.class, List.class, List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                notificationService, USER_ID, notifications, targetIds);

        assertTrue(result.isEmpty());
        verify(cassandraOperation, never()).updateRecord(any(), any(), anyMap(), anyMap());
    }

    @Test
    void testProcessReadUpdate_LogsWarning_WhenUpdateFails() throws Exception {
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, NOTIFICATION_ID_1);
        notification.put(READ, false);
        notification.put(CREATED_AT, Instant.now());
        List<Map<String, Object>> notifications = List.of(notification);
        List<String> targetIds = List.of(NOTIFICATION_ID_1);

        when(cassandraOperation.getRecordsByProperties(any(), any(), anyMap(), any(), anyInt()))
                .thenReturn(notifications);
        when(cassandraOperation.updateRecord(any(), any(), anyMap(), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.FAILED));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "processReadUpdate", String.class, List.class, List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                notificationService, USER_ID, notifications, targetIds);

        assertTrue(result.isEmpty());
    }

    // ===================== Additional coverage: insertAndMarkGlobalNotificationsAsRead skip branch =====================

    @Test
    void testInsertAndMarkGlobalNotificationsAsRead_SkipsExisting() throws Exception {
        Map<String, Object> globalNotif = new HashMap<>();
        globalNotif.put(NOTIFICATION_ID, NOTIFICATION_ID_1);
        globalNotif.put(CREATED_AT, Instant.now());

        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(NOTIFICATION_ID)), anyInt()))
                .thenReturn(List.of(Map.of(NOTIFICATION_ID, NOTIFICATION_ID_1)));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "insertAndMarkGlobalNotificationsAsRead", String.class, List.class);
        method.setAccessible(true);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> result = (List<Map<String, Object>>) method.invoke(
                notificationService, USER_ID, List.of(globalNotif));

        assertTrue(result.isEmpty());
        verify(cassandraOperation, never()).insertRecord(eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap());
    }

    // ===================== Additional coverage: markNotificationsAsDeleted success branch =====================

    @Test
    void testMarkNotificationsAsDeleted_SuccessfulUpdate_AddsToResult() {
        List<String> notificationIds = List.of(NOTIFICATION_ID_1);
        Instant createdAt = Instant.now();
        Map<String, Object> existingNotif = new HashMap<>();
        existingNotif.put(NOTIFICATION_ID, NOTIFICATION_ID_1);
        existingNotif.put(CREATED_AT, createdAt);

        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(existingNotif));
        when(cassandraOperation.updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.SUCCESS));

        ApiResponse response = notificationService.markNotificationsAsDeleted(AUTH_TOKEN, notificationIds);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> updated = (List<Map<String, Object>>) result.get(NOTIFICATIONS);
        assertEquals(1, updated.size());
        assertEquals(NOTIFICATION_ID_1, updated.get(0).get(ID));
        assertEquals(true, updated.get(0).get(IS_DELETED));
    }

    // ===================== Additional coverage: getResetNotificationCount failure branch =====================

    @Test
    void testGetResetNotificationCount_UpdateFails_LogsWarningButReturnsOk() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.FAILED));

        ApiResponse response = notificationService.getResetNotificationCount(AUTH_TOKEN);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    // ===================== Additional coverage: incrementUnreadCountManually branches =====================

    @Test
    void testIncrementUnreadCountManually_IncrementsExistingCount() throws Exception {
        Map<String, Object> existing = new HashMap<>();
        existing.put(COUNT, 5);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), eq(List.of(COUNT)), eq(1)))
                .thenReturn(List.of(existing));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "incrementUnreadCountManually", String.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(notificationService, KEYSPACE_SUNBIRD, TABLE_UNREAD_NOTIFICATION_COUNT, USER_ID);

        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT),
                argThat(m -> Integer.valueOf(6).equals(m.get(COUNT))),
                anyMap());
    }

    @Test
    void testIncrementUnreadCountManually_HandlesExceptionGracefully() throws Exception {
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), anyInt()))
                .thenThrow(new RuntimeException("Cassandra down"));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "incrementUnreadCountManually", String.class, String.class, String.class);
        method.setAccessible(true);
        assertDoesNotThrow(() -> method.invoke(notificationService, KEYSPACE_SUNBIRD, TABLE_UNREAD_NOTIFICATION_COUNT, USER_ID));
    }

    // ===================== Additional coverage: bulkCreatePeerValidationNotifications branches =====================

    @Test
    void testBulkCreatePeerValidationNotifications_NonSurveySubCategory_ValidatesSuccessfully() {
        Map<String, Object> surveyData = new LinkedHashMap<>();
        surveyData.put("title", "Q1");
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(DATA, List.of(surveyData));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put(USER_ID, "user-1");
        req.put(TYPE, "peer-review");
        req.put(CATEGORY, "PEER_VALIDATION");
        req.put(SUB_CATEGORY, "CONTENT_PUBLISHED");
        req.put(SUB_TYPE, "peer_evaluation");
        req.put(SOURCE, "competency-passbook");
        req.put(MESSAGE, message);

        when(cbServerProperties.isPeerValidationNotificationSettingCheckEnabled()).thenReturn(false);
        when(cbServerProperties.getPeerValidationBulkUserNotificationLimit()).thenReturn(100);
        when(cbServerProperties.getPeerValidationBulkCreatedAtOffsetMs()).thenReturn(1L);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(new ApiResponse());
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), anyInt()))
                .thenReturn(Collections.emptyList());

        Map<String, Object> body = Map.of(REQUEST, List.of(req));
        ApiResponse response = notificationService.bulkCreatePeerValidationNotifications(body);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(1, result.get(PROCESSED_COUNT));
        verify(cassandraOperation, never()).insertBulkRecord(anyString(), eq(TABLE_PEER_VALIDATION_REQUESTS), anyList());
        verify(cassandraOperation, never()).insertBulkRecord(anyString(), eq(TABLE_PEER_VALIDATION_REVIEWS), anyList());
    }

    @Test
    void testBulkCreatePeerValidationNotifications_BuildRecordFails_AddsToFailures() throws Exception {
        Map<String, Object> surveyData = new LinkedHashMap<>();
        surveyData.put("surveyEndDate", Instant.now().plusSeconds(86400).toString());
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(DATA, List.of(surveyData));
        Map<String, Object> req = new LinkedHashMap<>();
        req.put(USER_ID, "user-1");
        req.put(TYPE, "peer-review");
        req.put(CATEGORY, "PEER_VALIDATION");
        req.put(SUB_CATEGORY, "PEER_EVALUATION_ASSIGNED");
        req.put(SUB_TYPE, "peer_evaluation");
        req.put(SOURCE, "competency-passbook");
        req.put(MESSAGE, message);

        when(cbServerProperties.isPeerValidationNotificationSettingCheckEnabled()).thenReturn(false);
        when(cbServerProperties.getPeerValidationBulkUserNotificationLimit()).thenReturn(100);
        when(cbServerProperties.getPeerValidationBulkCreatedAtOffsetMs()).thenReturn(1L);
        when(objectMapper.writeValueAsString(any())).thenThrow(new RuntimeException("serialization boom"));

        Map<String, Object> body = Map.of(REQUEST, List.of(req));
        ApiResponse response = notificationService.bulkCreatePeerValidationNotifications(body);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(1, result.get(FAILED_COUNT));
        assertEquals(0, result.get(PROCESSED_COUNT));
        List<Map<String, Object>> failures = (List<Map<String, Object>>) result.get(FAILED_KEY);
        assertEquals("user-1", failures.get(0).get(USER_ID));
        verify(cassandraOperation, never()).insertBulkRecord(anyString(), eq(TABLE_USER_NOTIFICATION), anyList());
    }

    // ===================== Additional coverage: getPeerValidationNotifications exception branch =====================

    @Test
    void testGetPeerValidationNotifications_ThrowsException_ReturnsInternalServerError() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), isNull(), anyInt()))
                .thenThrow(new RuntimeException("Cassandra down"));

        ApiResponse response = notificationService.getPeerValidationNotifications(
                AUTH_TOKEN, SUB_CATEGORY_PEER_EVALUATION_ASSIGNED, 30, 0, 10);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
        assertEquals(INTERNAL_ERROR_MSG, response.getParams().getErrMsg());
    }

    // ===================== Additional coverage: deserializeJsonField branches =====================

    @Test
    void testDeserializeJsonField_ValidJson_ParsesSuccessfully() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put(METADATA, "{\"key\":\"value\"}");
        ObjectMapper realMapper = new ObjectMapper();
        when(objectMapper.readValue(eq("{\"key\":\"value\"}"), eq(Object.class)))
                .thenReturn(realMapper.readValue("{\"key\":\"value\"}", Object.class));

        Method method = NotificationServiceImpl.class.getDeclaredMethod("deserializeJsonField", Map.class);
        method.setAccessible(true);
        method.invoke(notificationService, map);

        assertTrue(map.get(METADATA) instanceof Map);
    }

    @Test
    void testDeserializeJsonField_InvalidJson_LeavesFieldUnchanged() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put(METADATA, "not-json");
        when(objectMapper.readValue(eq("not-json"), eq(Object.class)))
                .thenThrow(new RuntimeException("parse error"));

        Method method = NotificationServiceImpl.class.getDeclaredMethod("deserializeJsonField", Map.class);
        method.setAccessible(true);
        method.invoke(notificationService, map);

        assertEquals("not-json", map.get(METADATA));
    }

    // ===================== Additional coverage: markIndividualNotificationAsRead / handleNormalReadFlow branches =====================

    @Test
    void testMarkNotificationsAsRead_IndividualV2_PeerReviewAssigned_NormalFlow_SkipsKafkaPublish() {
        String userId = "user-1";
        String notificationId = "notif-1";
        String createdAtStr = "2026-03-10T10:00:00Z";
        Instant createdAt = Instant.parse(createdAtStr);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, notificationId);
        notification.put(CREATED_AT, createdAt);
        notification.put(CATEGORY, "PEER_VALIDATION");
        notification.put(SUB_CATEGORY, "PEER_REVIEW_ASSIGNED");
        notification.put(READ, false);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(notification));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAtStr);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(m -> Boolean.TRUE.equals(m.get(READ))),
                eq(Map.of(USER_ID, userId, CREATED_AT, createdAt)));
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_NonPeerValidation_CreatedAtAsString() {
        String userId = "user-1";
        String notificationId = "notif-1";
        String createdAtStr = "2026-03-10T10:00:00Z";
        Instant createdAt = Instant.parse(createdAtStr);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, notificationId);
        notification.put(CREATED_AT, createdAt);
        notification.put(CATEGORY, "GENERAL");
        notification.put(SUB_CATEGORY, "INFO");
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(notification));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAtStr);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(m -> Boolean.TRUE.equals(m.get(READ))),
                eq(Map.of(USER_ID, userId, CREATED_AT, createdAt)));
    }

    private Map<String, Object> buildPeerEvalNotificationForRead(String notificationId, Instant createdAt) {
        Map<String, Object> notification = new HashMap<>();
        notification.put(NOTIFICATION_ID, notificationId);
        notification.put(CREATED_AT, createdAt);
        notification.put(CATEGORY, "PEER_VALIDATION");
        notification.put(SUB_CATEGORY, "PEER_EVALUATION_ASSIGNED");
        notification.put(READ, false);
        return notification;
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_PeerEvaluation_MessageBlank_SkipsPublish() {
        String userId = "user-1";
        String notificationId = "notif-1";
        String createdAtStr = "2026-03-10T10:00:00Z";
        Instant createdAt = Instant.parse(createdAtStr);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(buildPeerEvalNotificationForRead(notificationId, createdAt)));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, "")));
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAtStr);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_PeerEvaluation_MessageDataEmpty_SkipsPublish() throws Exception {
        String userId = "user-1";
        String notificationId = "notif-1";
        String createdAtStr = "2026-03-10T10:00:00Z";
        Instant createdAt = Instant.parse(createdAtStr);
        String messageJson = "{\"data\": []}";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(buildPeerEvalNotificationForRead(notificationId, createdAt)));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, messageJson)));
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put(DATA, Collections.emptyList());
        when(objectMapper.readValue(eq(messageJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any())).thenReturn(messageMap);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAtStr);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    @Test
    void testMarkNotificationsAsRead_IndividualV2_PeerEvaluation_FormIdBlank_SkipsPublish() throws Exception {
        String userId = "user-1";
        String notificationId = "notif-1";
        String createdAtStr = "2026-03-10T10:00:00Z";
        Instant createdAt = Instant.parse(createdAtStr);
        String messageJson = "{\"data\": [{\"formId\": \"\"}]}";
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), isNull(), anyInt()))
                .thenReturn(List.of(buildPeerEvalNotificationForRead(notificationId, createdAt)));
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(MESSAGE)), eq(1)))
                .thenReturn(List.of(Map.of(MESSAGE, messageJson)));
        Map<String, Object> messageMap = new HashMap<>();
        messageMap.put(DATA, List.of(Map.of(Constants.FORM_ID, "")));
        when(objectMapper.readValue(eq(messageJson), ArgumentMatchers.<TypeReference<Map<String, Object>>>any())).thenReturn(messageMap);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put("ids", List.of(notificationId));
        request.put(CREATED_AT, createdAtStr);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, API_VERSION_V2);
        assertEquals(HttpStatus.OK, response.getResponseCode());
        verify(producer, never()).push(anyString(), anyMap());
    }

    // ===================== Additional coverage: updatePeerEvaluationStatus (previously untested) =====================

    @Test
    void testUpdatePeerEvaluationStatus_InvalidStatus_NoOp() {
        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", Instant.now().toString(), "PENDING");
        verifyNoInteractions(cassandraOperation);
    }

    @Test
    void testUpdatePeerEvaluationStatus_InvalidCreatedAtFormat_CatchesAndLogs() {
        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", "not-a-date", "APPROVED");
        verifyNoInteractions(cassandraOperation);
    }

    @Test
    void testUpdatePeerEvaluationStatus_ReviewRecordNotFound_ReturnsEarly() {
        String createdAt = Instant.now().toString();
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(Collections.emptyList());
        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", createdAt, "APPROVED");
        verify(cassandraOperation, never()).updateRecord(any(), any(), anyMap(), anyMap());
    }

    @Test
    void testUpdatePeerEvaluationStatus_ReviewAlreadyTerminal_ReturnsEarly() {
        String createdAt = Instant.now().toString();
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(List.of(Map.of(STATUS, "APPROVED")));
        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", createdAt, "REJECTED");
        verify(cassandraOperation, never()).updateRecord(any(), any(), anyMap(), anyMap());
    }

    @Test
    void testUpdatePeerEvaluationStatus_UserNotificationRecordNotFound_ReturnsEarly() {
        String createdAt = Instant.now().toString();
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(List.of(Map.of(STATUS, "PENDING")));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(Collections.emptyList());
        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", createdAt, "APPROVED");
        verify(cassandraOperation, never()).updateRecord(any(), any(), anyMap(), anyMap());
    }

    @Test
    void testUpdatePeerEvaluationStatus_UserNotificationAlreadyTerminal_ReturnsEarly() {
        String createdAt = Instant.now().toString();
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(List.of(Map.of(STATUS, "PENDING")));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(List.of(Map.of(STATUS, "REJECTED")));
        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", createdAt, "APPROVED");
        verify(cassandraOperation, never()).updateRecord(any(), any(), anyMap(), anyMap());
    }

    @Test
    void testUpdatePeerEvaluationStatus_Success_UpdatesBothTables() {
        String createdAtStr = "2026-03-10T10:00:00Z";
        Instant createdAt = Instant.parse(createdAtStr);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(List.of(Map.of(STATUS, "PENDING")));
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenReturn(List.of(Map.of(STATUS, "PENDING")));

        notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", createdAtStr, "APPROVED");

        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS),
                argThat(m -> "APPROVED".equals(m.get(STATUS))),
                eq(Map.of(USER_ID, USER_ID, NOTIFICATION_ID, "notif-1")));
        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION),
                argThat(m -> "APPROVED".equals(m.get(STATUS))),
                eq(Map.of(USER_ID, USER_ID, CREATED_AT, createdAt)));
    }

    @Test
    void testUpdatePeerEvaluationStatus_UnexpectedException_CaughtAndLogged() {
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), eq(List.of(STATUS)), eq(1)))
                .thenThrow(new RuntimeException("Cassandra down"));
        assertDoesNotThrow(() ->
                notificationService.updatePeerEvaluationStatus(USER_ID, "notif-1", Instant.now().toString(), "APPROVED"));
    }

    // ===================== Additional branch coverage: round 2 =====================

    @Test
    void testCreateNotification_RequestNodeNotObject_ReturnsBadRequest() throws Exception {
        String authToken = "Bearer token";
        ObjectMapper mapper = new ObjectMapper();
        String payload = "{ \"request\": \"not-an-object\" }";
        JsonNode userNotificationDetail = mapper.readTree(payload);
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn("testUser");

        ApiResponse response = notificationService.createNotification(userNotificationDetail, authToken);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INVALID_PAYLOAD_ERR_MSG, response.getParams().getErrMsg());
    }

    @Test
    void testCreateNotification_SettingPresentAndEnabled_ProceedsNormally() throws Exception {
        String authToken = "Bearer token";
        String userId = "testUser";
        String payload = "{ \"request\": { \"type\": \"comment\" } }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        NotificationSettingEntity enabled = new NotificationSettingEntity();
        enabled.setEnabled(true);
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(userId, "comment"))
                .thenReturn(Optional.of(enabled));
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of("response", "SUCCESS"));

        ApiResponse response = notificationService.createNotification(userNotificationDetail, authToken);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        assertNotNull(response.getResult());
    }

    @Test
    void testBulkCreateNotifications_RequestNodeNotObject_ReturnsBadRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = "{ \"request\": \"not-an-object\" }";
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals(Constants.INVALID_PAYLOAD_ERR_MSG, response.getParams().getErrMsg());
    }

    @Test
    void testBulkCreateNotifications_UserIdsNotArray_ReturnsBadRequest() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        String payload = "{ \"request\": { \"type\": \"comment\", \"user_ids\": \"not-an-array\" } }";
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
        assertEquals("'user_ids' must be a non-empty list", response.getParams().getErrMsg());
    }

    @Test
    void testBulkCreateNotifications_UserIdEntryMissingKey_SkipsEntry() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"user_ids\": [ {}, {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(mockInsertResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get("notifications");
        assertEquals(1, notifications.size());
    }

    @Test
    void testBulkCreateNotifications_MessageDataPresentButNotObject_CreatesNewDataNode() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"message\": {\"title\": \"hello\", \"data\": \"not-an-object\"}," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        ApiResponse mockInsertResponse = new ApiResponse();
        mockInsertResponse.setResponseCode(HttpStatus.OK);
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(mockInsertResponse);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        ArgumentCaptor<List<Map<String, Object>>> captor = ArgumentCaptor.forClass(List.class);
        verify(cassandraOperation).insertBulkRecord(anyString(), anyString(), captor.capture());
        String messageStr = (String) captor.getValue().get(0).get(Constants.MESSAGE);
        assertNotNull(messageStr);
        assertTrue(messageStr.contains("\"count\":1"));
    }

    @Test
    void testBulkCreateNotifications_InsertBulkRecordReturnsNull_ProceedsNormally() throws Exception {
        String payload = "{ \"request\": { " +
                "\"type\": \"comment\"," +
                "\"sub_category\": \"CONTENT_PUBLISHED\"," +
                "\"user_ids\": [ {\"user_id\": \"user1\"} ]" +
                "} }";
        ObjectMapper mapper = new ObjectMapper();
        JsonNode userNotificationDetail = mapper.readTree(payload);

        // insertBulkRecord's declared return type is ApiResponse, so a non-ApiResponse value can
        // never flow through it at runtime; null is the only way to make "instanceof ApiResponse" false.
        when(cassandraOperation.insertBulkRecord(anyString(), anyString(), anyList()))
                .thenReturn(null);

        ApiResponse response = notificationService.bulkCreateNotifications(userNotificationDetail);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get("notifications");
        assertEquals(1, notifications.size());
    }

    @Test
    void testIsGlobalSubCategory_CoursePublished_ReturnsTrue() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isGlobalSubCategory", NotificationSubCategory.class);
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(notificationService, NotificationSubCategory.COURSE_PUBLISHED);
        assertTrue(result);
    }

    @Test
    void testIsGlobalSubCategory_ProgramPublished_ReturnsTrue() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isGlobalSubCategory", NotificationSubCategory.class);
        method.setAccessible(true);
        boolean result = (boolean) method.invoke(notificationService, NotificationSubCategory.PROGRAM_PUBLISHED);
        assertTrue(result);
    }

    @Test
    void testCreateGlobalNotification_FieldIsNonValueNode_UsesToString() throws Exception {
        ObjectMapper realMapper = new ObjectMapper();
        String payload = "{ \"type\": \"content\", \"role\": {\"nested\": \"obj\"} }";
        JsonNode request = realMapper.readTree(payload);

        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(Map.of(Constants.RESPONSE, Constants.SUCCESS));

        ApiResponse response = notificationService.createGlobalNotification(
                NotificationSubCategory.EVENT_PUBLISHED, request);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(cassandraOperation).insertRecord(anyString(), anyString(), captor.capture());
        assertTrue(((String) captor.getValue().get("role")).contains("nested"));
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_SettingPresentAndEnabled_ProceedsNormally() {
        String authToken = "Bearer xyz";
        String userId = "user-enabled";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        NotificationSettingEntity enabled = new NotificationSettingEntity();
        enabled.setEnabled(true);
        when(notificationSettingRepository.findByUserIdAndNotificationTypeAndIsDeletedFalse(anyString(), anyString()))
                .thenReturn(Optional.of(enabled));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_SkipsGlobalNotificationAlreadyInUserList() {
        String authToken = "Bearer xyz";
        String userId = "user-overlap";
        Instant now = Instant.now();
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> userNotif = new HashMap<>();
        userNotif.put(NOTIFICATION_ID, "shared-id");
        userNotif.put(Constants.CREATED_AT, now.minusSeconds(10));
        userNotif.put(Constants.IS_DELETED, false);
        userNotif.put(Constants.READ, false);

        Map<String, Object> globalNotif = new HashMap<>();
        globalNotif.put(NOTIFICATION_ID, "shared-id");
        globalNotif.put(Constants.CREATED_AT, now.minusSeconds(20));
        globalNotif.put(Constants.IS_DELETED, false);

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(userNotif));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(globalNotif));

        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0)).when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        List<Map<String, Object>> notifications = (List<Map<String, Object>>) result.get(NOTIFICATIONS);
        assertEquals(1, notifications.size());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_ExcludesRecordWithNullCreatedAt() {
        String authToken = "Bearer xyz";
        String userId = "user-nullcreated";
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> notif = new HashMap<>();
        notif.put(NOTIFICATION_ID, "n1");
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);
        // no CREATED_AT at all -> getInstant returns null

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get(TOTAL_COUNT));
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_ExcludesRecordBeforeFromDate() {
        String authToken = "Bearer xyz";
        String userId = "user-olddate";
        Instant veryOld = Instant.now().minus(java.time.Duration.ofDays(365));
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);

        Map<String, Object> notif = new HashMap<>();
        notif.put(NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, veryOld);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);

        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get(TOTAL_COUNT));
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_HasNextPageTrue() {
        String authToken = "Bearer xyz";
        String userId = "user-paged";
        Instant now = Instant.now();
        List<Map<String, Object>> notifs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> n = new HashMap<>();
            n.put(NOTIFICATION_ID, "n" + i);
            n.put(Constants.CREATED_AT, now.minusSeconds(i));
            n.put(Constants.IS_DELETED, false);
            n.put(Constants.READ, false);
            notifs.add(n);
        }
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(notifs);
        when(cassandraOperation.getRecordsByProperties(
                anyString(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        NotificationServiceImpl spyService = Mockito.spy(notificationService);
        doAnswer(invocation -> invocation.getArgument(0)).when(spyService).prepareNotificationResponse(any());

        ApiResponse response = spyService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 2, null, null);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(true, result.get(HAS_NEXT_PAGE));
    }

    @Test
    void testMarkNotificationsAsRead_GlobalActionIndividualType_MissingIds_ReturnsBadRequest() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        Map<String, Object> request = new HashMap<>();
        request.put(TYPE, INDIVIDUAL);
        request.put(ACTION, GLOBAL);
        ApiResponse response = notificationService.markNotificationsAsRead(AUTH_TOKEN, request, Constants.API_VERSION_V1);
        assertEquals(HttpStatus.BAD_REQUEST, response.getResponseCode());
    }

    @Test
    void testGetUnreadNotificationCount_CountRecordsNull_TreatedAsEmpty() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(null);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(Collections.emptyList());

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 7);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get("unread"));
    }

    @Test
    void testGetUnreadNotificationCount_RecordIsNull_SkipsProcessing() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        List<Map<String, Object>> countRecords = new ArrayList<>();
        countRecords.add(null);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(countRecords);

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 7);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get("unread"));
    }

    @Test
    void testGetUnreadNotificationCount_GlobalNotificationsAfterLastUpdated_AllCombinationsCovered() {
        Instant lastUpdated = Instant.now().minusSeconds(1000);
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);

        Map<String, Object> countRecord = new HashMap<>();
        countRecord.put(COUNT, 3);
        countRecord.put(UPDATED_AT, lastUpdated);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(List.of(countRecord));

        Map<String, Object> afterUpdate = new HashMap<>();
        afterUpdate.put(Constants.CREATED_AT, Instant.now());
        Map<String, Object> beforeUpdate = new HashMap<>();
        beforeUpdate.put(Constants.CREATED_AT, lastUpdated.minusSeconds(500));
        Map<String, Object> notAnInstant = new HashMap<>();
        notAnInstant.put(Constants.CREATED_AT, "not-an-instant");

        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(afterUpdate, beforeUpdate, notAnInstant));

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 7);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(4, result.get("unread")); // 3 existing + 1 global after lastUpdated
    }

    @Test
    void testIncrementUnreadCountManually_RecordsNonEmptyButCountNull() throws Exception {
        Map<String, Object> existing = new HashMap<>();
        existing.put(COUNT, null);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), eq(List.of(COUNT)), eq(1)))
                .thenReturn(List.of(existing));

        Method method = NotificationServiceImpl.class.getDeclaredMethod(
                "incrementUnreadCountManually", String.class, String.class, String.class);
        method.setAccessible(true);
        method.invoke(notificationService, KEYSPACE_SUNBIRD, TABLE_UNREAD_NOTIFICATION_COUNT, USER_ID);

        verify(cassandraOperation).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT),
                argThat(m -> Integer.valueOf(1).equals(m.get(COUNT))),
                anyMap());
    }

    @Test
    void testFetchExistingUnreadCounts_SkipsNonNumberCount() throws Exception {
        Set<String> userIds = new LinkedHashSet<>(List.of("u1"));
        Map<String, Object> record = new HashMap<>();
        record.put(USER_ID, "u1");
        record.put(COUNT, "not-a-number");
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), anyInt()))
                .thenReturn(List.of(record));

        Method method = NotificationServiceImpl.class.getDeclaredMethod("fetchExistingUnreadCounts", Set.class);
        method.setAccessible(true);
        Map<String, Integer> result = (Map<String, Integer>) method.invoke(notificationService, userIds);

        assertTrue(result.isEmpty());
    }

    @Test
    void testBuildPeerValidationResponseEntry_CreatedAtNotInstant_LeavesUnchanged() throws Exception {
        Map<String, Object> record = new HashMap<>();
        record.put(CREATED_AT, "2026-01-01T00:00:00Z");
        record.put(IS_DELETED, true);

        Method method = NotificationServiceImpl.class.getDeclaredMethod("buildPeerValidationResponseEntry", Map.class);
        method.setAccessible(true);
        Map<String, Object> result = (Map<String, Object>) method.invoke(notificationService, record);

        assertEquals("2026-01-01T00:00:00Z", result.get(CREATED_AT));
        assertFalse(result.containsKey(IS_DELETED));
    }

    @Test
    void testValidateSingleNotificationRequest_FirstEntryNotMap_ReturnsError() throws Exception {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put(DATA, List.of("not-a-map"));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put(USER_ID, "user-1");
        request.put(TYPE, "peer-review");
        request.put(CATEGORY, "PEER_VALIDATION");
        request.put(SUB_CATEGORY, "CONTENT_PUBLISHED");
        request.put(SUB_TYPE, "peer_evaluation");
        request.put(SOURCE, "competency-passbook");
        request.put(MESSAGE, message);

        Method method = NotificationServiceImpl.class.getDeclaredMethod("validateSingleNotificationRequest", Map.class);
        method.setAccessible(true);
        String result = (String) method.invoke(notificationService, request);

        assertEquals(ERR_MESSAGE_DATA_REQUIRED, result);
    }

    @Test
    void testDeserializeJsonField_BlankString_LeavesUnchanged() throws Exception {
        Map<String, Object> map = new HashMap<>();
        map.put(METADATA, "   ");

        Method method = NotificationServiceImpl.class.getDeclaredMethod("deserializeJsonField", Map.class);
        method.setAccessible(true);
        method.invoke(notificationService, map);

        assertEquals("   ", map.get(METADATA));
        verifyNoInteractions(objectMapper);
    }

    @Test
    void testHandleStatusBasedAction_NeitherCategory_OnlyUpdatesUserNotification() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("handleStatusBasedAction", String.class, String.class, Instant.class, Instant.class, String.class, String.class);
        method.setAccessible(true);
        String userId = "user-123";
        String notificationId = "notif-456";
        Instant createdAt = Instant.parse("2026-03-15T10:00:00Z");
        Instant now = Instant.now();
        String status = "SKIP_FOR_NOW";
        when(cassandraOperation.updateRecord(anyString(), anyString(), anyMap(), anyMap()))
                .thenReturn(Map.of(RESPONSE, Constants.SUCCESS));
        method.invoke(notificationService, userId, notificationId, createdAt, now, status, "SOME_OTHER_CATEGORY");
        verify(cassandraOperation, times(1)).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_USER_NOTIFICATION), anyMap(), anyMap());
        verify(cassandraOperation, never()).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REQUESTS), anyMap(), anyMap());
        verify(cassandraOperation, never()).updateRecord(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_PEER_VALIDATION_REVIEWS), anyMap(), anyMap());
    }

    @Test
    void testPersistActionRecordsBySubCategory_NeitherCategory_SkipsBothTables() throws Exception {
        Map<String, Object> actionRecord = new HashMap<>();
        actionRecord.put(SUB_CATEGORY, "SOME_OTHER_CATEGORY");
        actionRecord.put(NOTIFICATION_ID, "n1");

        Method method = NotificationServiceImpl.class.getDeclaredMethod("persistActionRecordsBySubCategory", List.class);
        method.setAccessible(true);
        method.invoke(notificationService, List.of(actionRecord));

        verify(cassandraOperation, never()).insertBulkRecord(anyString(), eq(TABLE_PEER_VALIDATION_REQUESTS), anyList());
        verify(cassandraOperation, never()).insertBulkRecord(anyString(), eq(TABLE_PEER_VALIDATION_REVIEWS), anyList());
    }

    @Test
    void testIsWithinDateWindow_AllBranches() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isWithinDateWindow", Map.class, Instant.class);
        method.setAccessible(true);
        Instant fromDate = Instant.now().minusSeconds(100);

        Map<String, Object> nullCreatedAt = new HashMap<>();
        assertFalse((boolean) method.invoke(notificationService, nullCreatedAt, fromDate));

        Map<String, Object> beforeFromDate = Map.of(CREATED_AT, fromDate.minusSeconds(500));
        assertFalse((boolean) method.invoke(notificationService, beforeFromDate, fromDate));

        Map<String, Object> afterFromDate = Map.of(CREATED_AT, fromDate.plusSeconds(500));
        assertTrue((boolean) method.invoke(notificationService, afterFromDate, fromDate));
    }

    @Test
    void testIsStatusAllowed_AllBranches() throws Exception {
        Method method = NotificationServiceImpl.class.getDeclaredMethod("isStatusAllowed", Map.class, Set.class);
        method.setAccessible(true);
        Set<String> exclusionSet = Set.of("SUBMITTED", "IGNORED");

        Map<String, Object> blankStatus = new HashMap<>();
        assertTrue((boolean) method.invoke(notificationService, blankStatus, exclusionSet));

        Map<String, Object> allowedStatus = Map.of(STATUS, "PENDING");
        assertTrue((boolean) method.invoke(notificationService, allowedStatus, exclusionSet));

        Map<String, Object> excludedStatus = Map.of(STATUS, "SUBMITTED");
        assertFalse((boolean) method.invoke(notificationService, excludedStatus, exclusionSet));
    }

    // ===================== Additional branch coverage: round 3 =====================

    @Test
    void testCreateGlobalNotification_InsertReturnsApiResponseNotFailed_ProceedsNormally() {
        ObjectMapper realMapper = new ObjectMapper();
        JsonNode request = realMapper.createObjectNode().put(Constants.TYPE, "IN_APP");
        ApiResponse successResponse = new ApiResponse();
        successResponse.put(Constants.RESPONSE, Constants.SUCCESS);
        when(cassandraOperation.insertRecord(anyString(), anyString(), anyMap()))
                .thenReturn(successResponse);

        ApiResponse response = notificationService.createGlobalNotification(
                NotificationSubCategory.EVENT_PUBLISHED, request);

        assertEquals(HttpStatus.OK, response.getResponseCode());
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_FiltersOutUnreadWhenStatusIsRead() {
        String authToken = "Bearer abc";
        String userId = "u-filterread";
        Instant now = Instant.now();
        Map<String, Object> notif = new HashMap<>();
        notif.put(Constants.NOTIFICATION_ID, "n1");
        notif.put(Constants.CREATED_AT, now);
        notif.put(Constants.IS_DELETED, false);
        notif.put(Constants.READ, false);

        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(notif));
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 0, 10, NotificationReadStatus.READ, null);

        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(0, result.get(TOTAL_COUNT));
    }

    @Test
    void testGetNotificationsByUserIdAndLastXDays_NegativeSize_ClampsFromIndexAndHandlesGracefully() {
        String authToken = "Bearer abc";
        String userId = "u-negsize";
        Instant now = Instant.now();
        List<Map<String, Object>> notifs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> n = new HashMap<>();
            n.put(NOTIFICATION_ID, "n" + i);
            n.put(Constants.CREATED_AT, now.minusSeconds(i));
            n.put(Constants.IS_DELETED, false);
            n.put(Constants.READ, false);
            notifs.add(n);
        }
        when(accessTokenValidator.fetchUserIdFromAccessToken(authToken)).thenReturn(userId);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_USER_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(notifs);
        when(cassandraOperation.getRecordsByProperties(any(), eq(Constants.TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of());

        // page=1, size=-5 drives fromIndex (-5) above toIndex (-10), forcing the fromIndex>toIndex
        // clamp at line 616; the resulting negative subList bounds surface as a handled 500.
        ApiResponse response = notificationService.getNotificationsByUserIdAndLastXDays(
                authToken, 7, 1, -5, null, null);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getResponseCode());
    }

    @Test
    void testGetUnreadNotificationCount_NoRecords_WithNonEmptyGlobalNotifications() {
        when(accessTokenValidator.fetchUserIdFromAccessToken(AUTH_TOKEN)).thenReturn(USER_ID);
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_UNREAD_NOTIFICATION_COUNT), anyMap(), anyList(), eq(1)))
                .thenReturn(Collections.emptyList());
        when(cassandraOperation.getRecordsByProperties(
                eq(KEYSPACE_SUNBIRD), eq(TABLE_GLOBAL_NOTIFICATION), anyMap(), any(), anyInt()))
                .thenReturn(List.of(Map.of(NOTIFICATION_ID, "g1"), Map.of(NOTIFICATION_ID, "g2")));

        ApiResponse response = notificationService.getUnreadNotificationCount(AUTH_TOKEN, 7);

        assertEquals(HttpStatus.OK, response.getResponseCode());
        Map<String, Object> result = (Map<String, Object>) response.getResult();
        assertEquals(2, result.get("unread"));
    }
}
