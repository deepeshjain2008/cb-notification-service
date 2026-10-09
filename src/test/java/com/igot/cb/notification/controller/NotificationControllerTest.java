

package com.igot.cb.notification.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.igot.cb.notification.enums.NotificationReadStatus;
import com.igot.cb.notification.service.NotificationService;
import com.igot.cb.peervalidationcleanup.service.PeerValidationCleanupService;
import com.igot.cb.util.Constants;

import org.igot.common.ApiResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotificationControllerTest {

    @InjectMocks
    private NotificationController notificationController;

    @Mock
    private NotificationService notificationService;

    @Mock
    private PeerValidationCleanupService peerValidationCleanupService;

    private ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testCreateNotification() {
        ObjectNode json = objectMapper.createObjectNode();
        String token = "token";
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.CREATED);

        when(notificationService.createNotification(json, token)).thenReturn(apiResponse);

        ResponseEntity<ApiResponse> response = notificationController.createNotification(json, token);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
    }

    @Test
    void testCreateBulkNotification() {
        ObjectNode json = objectMapper.createObjectNode();
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.bulkCreateNotifications(json)).thenReturn(apiResponse);

        ResponseEntity<ApiResponse> response = notificationController.createBulkNotification(json);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testReadByUserIdAndNotificationId() {
        String notifId = "notif-1";
        String token = "token";
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.readByUserIdAndNotificationId(notifId, token)).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.readByUserIdAndNotificationId(notifId, token);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetLastXDaysNotifications() {
        String token = "token";
        int days = 7, page = 0, size = 10;
        NotificationReadStatus status = NotificationReadStatus.BOTH;
        String subType = null;

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.getNotificationsByUserIdAndLastXDays(
                token, days, page, size, status, subType))
                .thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.getLastXDaysNotifications(
                token, days, page, size, status, subType);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testMarkNotificationsAsRead() {
        String token = "token";
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.TYPE, Constants.ALL);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(Constants.REQUEST, request);

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.markNotificationsAsRead(token, request, Constants.API_VERSION_V1)).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.markNotificationsAsRead(token, requestBody);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testMarkNotificationsAsDeleted() {
        String token = "token";
        List<String> ids = Arrays.asList("id1", "id2");
        Map<String, Object> innerRequest = new HashMap<>();
        innerRequest.put(Constants.IDS, ids);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(Constants.REQUEST, innerRequest);

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.markNotificationsAsDeleted(token, ids)).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.markNotificationsAsDeleted(token, requestBody);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetUnreadNotificationCount() {
        String token = "token";
        int days = 3;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.getUnreadNotificationCount(token, days)).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.getUnreadNotificationCount(token, days);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetResetNotificationCount() {
        String token = "token";
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);

        when(notificationService.getResetNotificationCount(token)).thenReturn(apiResponse);

        ResponseEntity<?> response = notificationController.getResetNotificationCount(token);

        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testGetPeerValidationNotifications_withPeerEvaluationAssigned() {
        String token = "test-token";
        String subType = Constants.SUB_CATEGORY_PEER_EVALUATION_ASSIGNED;
        int days = 7;
        int page = 0;
        int size = 10;

        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setId(Constants.PEER_VALIDATION_LIST_API_ID);
        apiResponse.setVer("1.0");
        apiResponse.setTs("2026-03-12T10:00:00Z");
        apiResponse.setResponseCode(HttpStatus.OK);
        
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.NOTIFICATIONS, List.of());
        result.put(Constants.TOTAL_COUNT, 0);
        result.put(Constants.PAGE, page);
        result.put(Constants.SIZE, size);
        result.put(Constants.HAS_NEXT_PAGE, false);
        apiResponse.setResult(result);

        when(notificationService.getPeerValidationNotifications(token, subType, days, page, size))
                .thenReturn(apiResponse);

        ResponseEntity<Map<String, Object>> response = 
                notificationController.getPeerValidationNotifications(token, subType, days, page, size);

        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(Constants.PEER_VALIDATION_LIST_API_ID, response.getBody().get(Constants.ID));
        assertEquals("1.0", response.getBody().get(Constants.VER));
        assertEquals(HttpStatus.OK, response.getBody().get(Constants.RESPONSE_CODE));
        assertNotNull(response.getBody().get(Constants.RESULT));
        
        verify(notificationService, times(1))
                .getPeerValidationNotifications(token, subType, days, page, size);
    }

    @Test
    void testGetPeerValidationNotifications_withPeerReviewAssigned() {
        String token = "test-token";
        String subType = Constants.SUB_CATEGORY_PEER_REVIEW_ASSIGNED;
        int days = 30;
        int page = 1;
        int size = 20;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setId(Constants.PEER_VALIDATION_LIST_API_ID);
        apiResponse.setVer("1.0");
        apiResponse.setTs("2026-03-12T10:00:00Z");
        apiResponse.setResponseCode(HttpStatus.OK);
        List<Map<String, Object>> notifications = new ArrayList<>();
        Map<String, Object> notification1 = new HashMap<>();
        notification1.put(Constants.NOTIFICATION_ID, "notif-1");
        notification1.put(Constants.USER_ID, "user001");
        notification1.put(Constants.STATUS, "PENDING");
        notifications.add(notification1);
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.NOTIFICATIONS, notifications);
        result.put(Constants.TOTAL_COUNT, 1);
        result.put(Constants.PAGE, page);
        result.put(Constants.SIZE, size);
        result.put(Constants.HAS_NEXT_PAGE, false);
        apiResponse.setResult(result);
        when(notificationService.getPeerValidationNotifications(token, subType, days, page, size))
                .thenReturn(apiResponse);
        ResponseEntity<Map<String, Object>> response = 
                notificationController.getPeerValidationNotifications(token, subType, days, page, size);
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        Map<String, Object> responseResult = (Map<String, Object>) response.getBody().get(Constants.RESULT);
        assertNotNull(responseResult);
        assertEquals(1, responseResult.get(Constants.TOTAL_COUNT));
        verify(notificationService, times(1))
                .getPeerValidationNotifications(token, subType, days, page, size);
    }

    @Test
    void testGetPeerValidationNotifications_withDefaultParameters() {
        String token = "test-token";
        String subType = Constants.SUB_CATEGORY_PEER_EVALUATION_ASSIGNED;
        int days = 7;
        int page = 0;
        int size = 10;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setId(Constants.PEER_VALIDATION_LIST_API_ID);
        apiResponse.setResponseCode(HttpStatus.OK);
        apiResponse.setResult(new HashMap<>());
        when(notificationService.getPeerValidationNotifications(token, subType, days, page, size))
                .thenReturn(apiResponse);
        ResponseEntity<Map<String, Object>> response = 
                notificationController.getPeerValidationNotifications(token, subType, days, page, size);
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(notificationService, times(1))
                .getPeerValidationNotifications(token, subType, days, page, size);
    }

    @Test
    void testGetPeerValidationNotifications_withBadRequest() {
        String token = "test-token";
        String subType = "INVALID_SUBTYPE";
        int days = 7;
        int page = 0;
        int size = 10;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setId(Constants.PEER_VALIDATION_LIST_API_ID);
        apiResponse.setResponseCode(HttpStatus.BAD_REQUEST);
        apiResponse.setResult(new HashMap<>());
        when(notificationService.getPeerValidationNotifications(token, subType, days, page, size))
                .thenReturn(apiResponse);
        ResponseEntity<Map<String, Object>> response = 
                notificationController.getPeerValidationNotifications(token, subType, days, page, size);
        assertNotNull(response);
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(notificationService, times(1))
                .getPeerValidationNotifications(token, subType, days, page, size);
    }

    @Test
    void testGetPeerValidationNotifications_withPaginationAndHasNextPage() {
        String token = "test-token";
        String subType = Constants.SUB_CATEGORY_PEER_EVALUATION_ASSIGNED;
        int days = 14;
        int page = 0;
        int size = 5;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setId(Constants.PEER_VALIDATION_LIST_API_ID);
        apiResponse.setVer("1.0");
        apiResponse.setResponseCode(HttpStatus.OK);
        List<Map<String, Object>> notifications = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Map<String, Object> notification = new HashMap<>();
            notification.put(Constants.NOTIFICATION_ID, "notif-" + i);
            notification.put(Constants.USER_ID, "user001");
            notification.put(Constants.STATUS, "PENDING");
            notifications.add(notification);
        }
        Map<String, Object> result = new HashMap<>();
        result.put(Constants.NOTIFICATIONS, notifications);
        result.put(Constants.TOTAL_COUNT, 15);
        result.put(Constants.PAGE, page);
        result.put(Constants.SIZE, size);
        result.put(Constants.HAS_NEXT_PAGE, true);
        apiResponse.setResult(result);
        when(notificationService.getPeerValidationNotifications(token, subType, days, page, size))
                .thenReturn(apiResponse);
        ResponseEntity<Map<String, Object>> response = 
                notificationController.getPeerValidationNotifications(token, subType, days, page, size);
        assertNotNull(response);
        assertEquals(HttpStatus.OK, response.getStatusCode());
        @SuppressWarnings("unchecked")
        Map<String, Object> responseResult = (Map<String, Object>) response.getBody().get(Constants.RESULT);
        assertNotNull(responseResult);
        assertEquals(15, responseResult.get(Constants.TOTAL_COUNT));
        assertEquals(true, responseResult.get(Constants.HAS_NEXT_PAGE));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> notificationsList = 
                (List<Map<String, Object>>) responseResult.get(Constants.NOTIFICATIONS);
        assertEquals(5, notificationsList.size());
        verify(notificationService, times(1))
                .getPeerValidationNotifications(token, subType, days, page, size);
    }

    @Test
    void testGetPeerValidationNotifications_verifyResponseBodyStructure() {
        String token = "test-token";
        String subType = Constants.SUB_CATEGORY_PEER_EVALUATION_ASSIGNED;
        int days = 7;
        int page = 0;
        int size = 10;
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setId(Constants.PEER_VALIDATION_LIST_API_ID);
        apiResponse.setVer("1.0");
        apiResponse.setTs("2026-03-12T10:00:00Z");
        apiResponse.setResponseCode(HttpStatus.OK);
        apiResponse.setResult(new HashMap<>());
        when(notificationService.getPeerValidationNotifications(token, subType, days, page, size))
                .thenReturn(apiResponse);
        ResponseEntity<Map<String, Object>> response = 
                notificationController.getPeerValidationNotifications(token, subType, days, page, size);
        assertNotNull(response);
        assertNotNull(response.getBody());
        Map<String, Object> responseBody = response.getBody();
        assertTrue(responseBody.containsKey(Constants.ID));
        assertTrue(responseBody.containsKey(Constants.VER));
        assertTrue(responseBody.containsKey(Constants.TS));
        assertTrue(responseBody.containsKey(Constants.PARAMS));
        assertTrue(responseBody.containsKey(Constants.RESPONSE_CODE));
        assertTrue(responseBody.containsKey(Constants.RESULT));
        assertFalse(responseBody.containsKey("response"), 
                "Response body should not contain duplicate 'response' field");
        verify(notificationService, times(1))
                .getPeerValidationNotifications(token, subType, days, page, size);
    }

    @Test
    void testMarkNotificationsAsReadV2() {
        String token = "token";
        Map<String, Object> request = new HashMap<>();
        request.put(Constants.TYPE, Constants.ALL);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put(Constants.REQUEST, request);
        ApiResponse apiResponse = new ApiResponse();
        apiResponse.setResponseCode(HttpStatus.OK);
        when(notificationService.markNotificationsAsRead(token, request, Constants.API_VERSION_V2)).thenReturn(apiResponse);
        ResponseEntity<?> response = notificationController.markNotificationsAsReadV2(token, requestBody);
        assertEquals(apiResponse, response.getBody());
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void testRunPeerValidationCleanup() {
        ResponseEntity<Void> response = notificationController.runPeerValidationCleanup();

        verify(peerValidationCleanupService, times(1)).runCleanup(any());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
    }
}