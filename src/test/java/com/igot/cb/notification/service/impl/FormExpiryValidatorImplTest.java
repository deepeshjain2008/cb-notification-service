package com.igot.cb.notification.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.elasticsearch.service.EsClientService;
import com.igot.cb.transactional.caffeinecache.NotificationMetadataCacheManager;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.Constants;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FormExpiryValidatorImplTest {

    @Mock
    private EsClientService esClientService;
    @Mock
    private CbServerProperties cbServerProperties;
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private NotificationMetadataCacheManager cacheManager;

    @InjectMocks
    private FormExpiryValidatorImpl validator;

    private static final String FORM_ID_1 = "form-001";
    private static final String INDEX_ALIAS = "forms-index";
    private static final String CONTEXT_TYPE = "survey";
    private static final long PAST_END_DATE = System.currentTimeMillis() - 86_400_000L;
    private static final long FUTURE_END_DATE = System.currentTimeMillis() + 86_400_000L;

    @BeforeEach
    void setUp() {
        when(cbServerProperties.getFormsEsFetchBatchSize()).thenReturn(10);
        when(cbServerProperties.getFormsEsIndexAlias()).thenReturn(INDEX_ALIAS);
        when(cbServerProperties.getFormsEsContextType()).thenReturn(CONTEXT_TYPE);
        when(cbServerProperties.getFormsEsFetchFields()).thenReturn(List.of(Constants.FORM_ID, Constants.END_DATE));
    }

    @Test
    void shouldReturnEmptyListWhenRecordsIsNull() {
        List<Map<String, Object>> result = validator.validateAndMarkExpired(null);
        assertTrue(result.isEmpty());
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldReturnEmptyListWhenRecordsIsEmpty() {
        List<Map<String, Object>> result = validator.validateAndMarkExpired(Collections.emptyList());
        assertTrue(result.isEmpty());
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldReturnOriginalListWhenNoPendingRecordsExist() {
        Map<String, Object> notification = Map.of(Constants.STATUS, Constants.STATUS_EXPIRED);
        List<Map<String, Object>> records = List.of(notification);
        List<Map<String, Object>> result = validator.validateAndMarkExpired(records);
        assertEquals(records, result);
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldMarkRecordExpiredWhenEndDateIsInPast() throws Exception {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(PAST_END_DATE));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldNotMarkRecordExpiredWhenEndDateIsInFuture() throws Exception {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(FUTURE_END_DATE));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
    }

    @Test
    void shouldNotMarkRecordExpiredWhenNoEndDateFound() throws Exception {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.empty());
        when(esClientService.searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList()))
                .thenReturn(Collections.emptyList());
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
    }

    @Test
    void shouldFetchFromEsOnCacheMissAndWarmCache() throws Exception {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        Map<String, Object> esDoc = Map.of(Constants.FORM_ID, FORM_ID_1, Constants.END_DATE, PAST_END_DATE);
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.empty());
        when(esClientService.searchByTerms(eq(INDEX_ALIAS), eq(Constants.FORM_ID), eq(List.of(FORM_ID_1)),
                eq(CONTEXT_TYPE), anyList())).thenReturn(List.of(esDoc));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
        verify(cacheManager).put(FORM_ID_1, PAST_END_DATE);
    }

    @Test
    void shouldFetchFromEsOnlyForCacheMissesWhenPartialCacheHit() throws Exception {
        String formId2 = "form-002";
        Map<String, Object> record1 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        Map<String, Object> record2 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + formId2 + "\"}");
        Map<String, Object> esDoc = Map.of(Constants.FORM_ID, formId2, Constants.END_DATE, PAST_END_DATE);
        when(objectMapper.readValue(eq("{\"formId\":\"" + FORM_ID_1 + "\"}"), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(objectMapper.readValue(eq("{\"formId\":\"" + formId2 + "\"}"), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, formId2));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(FUTURE_END_DATE));
        when(cacheManager.get(formId2)).thenReturn(Optional.empty());
        when(esClientService.searchByTerms(eq(INDEX_ALIAS), eq(Constants.FORM_ID), eq(List.of(formId2)),
                eq(CONTEXT_TYPE), anyList())).thenReturn(List.of(esDoc));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(record1, record2));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        assertEquals(Constants.STATUS_EXPIRED, result.get(1).get(Constants.STATUS));
        verify(esClientService, times(1)).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordWithBlankMetadata() {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "");
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordWithNullMetadata() {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, null);
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordAndContinueBatchWhenMetadataIsMalformed() throws Exception {
        Map<String, Object> badNotification = mutableRecord(Constants.STATUS_PENDING, "{invalid-json}");
        when(objectMapper.readValue(eq("{invalid-json}"), any(TypeReference.class)))
                .thenThrow(new RuntimeException("parse error"));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(badNotification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldExtractEndDateFromAdditionalPropertiesWhenTopLevelAbsent() throws Exception {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        Map<String, Object> esDoc = Map.of(
                Constants.FORM_ID, FORM_ID_1,
                Constants.ADDITIONAL_PROPERTIES, Map.of(Constants.END_DATE, PAST_END_DATE));
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.empty());
        when(esClientService.searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList()))
                .thenReturn(List.of(esDoc));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
    }

    @Test
    void shouldProcessMultipleBatchesWhenRecordsExceedBatchSize() throws Exception {
        when(cbServerProperties.getFormsEsFetchBatchSize()).thenReturn(2);
        List<Map<String, Object>> records = List.of(
                pendingRecordWithFormId("form-a"),
                pendingRecordWithFormId("form-b"),
                pendingRecordWithFormId("form-c"));
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenAnswer(invocation -> {
                    String json = invocation.getArgument(0);
                    String id = json.replaceAll(".*\"formId\":\"([^\"]+)\".*", "$1");
                    return Map.of(Constants.FORM_ID, id);
                });
        when(cacheManager.get(anyString())).thenReturn(Optional.empty());
        when(esClientService.searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList()))
                .thenReturn(Collections.emptyList());
        validator.validateAndMarkExpired(records);
        verify(esClientService, times(2)).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldMarkAllNotificationsExpiredWhenMultipleShareSameFormId() throws Exception {
        Map<String, Object> notif1 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        Map<String, Object> notif2 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        Map<String, Object> notif3 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + FORM_ID_1 + "\"}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(PAST_END_DATE));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notif1, notif2, notif3));
        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
        assertEquals(Constants.STATUS_EXPIRED, result.get(1).get(Constants.STATUS));
        assertEquals(Constants.STATUS_EXPIRED, result.get(2).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldHandlePendingStatusCaseInsensitively() throws Exception {
        Map<String, Object> notification = mutableRecord("pending", "{\"formId\":\"" + FORM_ID_1 + "\"}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, FORM_ID_1));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(PAST_END_DATE));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
    }

    private Map<String, Object> mutableRecord(String status, String metadata) {
        Map<String, Object> notification = new HashMap<>();
        notification.put(Constants.STATUS, status);
        notification.put(Constants.METADATA, metadata);
        notification.put(Constants.NOTIFICATION_ID, "notif-" + System.nanoTime());
        return notification;
    }

    private Map<String, Object> pendingRecordWithFormId(String formId) {
        return mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"" + formId + "\"}");
    }


    @Test
    void shouldMarkExpiredWhenFormIdExtractedFromMessageAndEndDateInPast() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null,
                "{\"data\":[{\"formId\":\"" + FORM_ID_1 + "\"}]}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of(Map.of(Constants.FORM_ID, FORM_ID_1))));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(PAST_END_DATE));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldNotMarkExpiredWhenFormIdExtractedFromMessageAndEndDateInFuture() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null,
                "{\"data\":[{\"formId\":\"" + FORM_ID_1 + "\"}]}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of(Map.of(Constants.FORM_ID, FORM_ID_1))));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(FUTURE_END_DATE));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
    }

    @Test
    void shouldSkipRecordWhenMetadataAbsentAndMessageIsBlank() {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null, "");

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordWhenMetadataAbsentAndMessageIsNull() {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null, null);

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordWhenMetadataAbsentAndMessageIsMalformed() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null, "{bad-json}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenThrow(new RuntimeException("parse error"));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordWhenMetadataAbsentAndMessageDataIsEmpty() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null,
                "{\"data\":[]}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of()));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipRecordWhenMetadataAbsentAndMessageDataHasNoFormId() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null,
                "{\"data\":[{\"courseName\":\"Test\"}]}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of(Map.of("courseName", "Test"))));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldFallbackToMessageWhenMetadataParseFails() throws Exception {
        String metaJson = "{invalid}";
        String msgJson = "{\"data\":[{\"formId\":\"" + FORM_ID_1 + "\"}]}";
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, metaJson, msgJson);
        when(objectMapper.readValue(eq(metaJson), any(TypeReference.class)))
                .thenThrow(new RuntimeException("parse error"));
        when(objectMapper.readValue(eq(msgJson), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of(Map.of(Constants.FORM_ID, FORM_ID_1))));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(PAST_END_DATE));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
    }

    @Test
    void shouldFallbackToMessageWhenMetadataHasNoFormId() throws Exception {
        String metaJson = "{\"someOtherField\":\"value\"}";
        String msgJson = "{\"data\":[{\"formId\":\"" + FORM_ID_1 + "\"}]}";
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, metaJson, msgJson);
        when(objectMapper.readValue(eq(metaJson), any(TypeReference.class)))
                .thenReturn(Map.of("someOtherField", "value"));
        when(objectMapper.readValue(eq(msgJson), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of(Map.of(Constants.FORM_ID, FORM_ID_1))));
        when(cacheManager.get(FORM_ID_1)).thenReturn(Optional.of(PAST_END_DATE));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));

        assertEquals(Constants.STATUS_EXPIRED, result.get(0).get(Constants.STATUS));
    }

    @Test
    void shouldSkipRecordWhenExtractedFormIdIsBlank() throws Exception {
        Map<String, Object> notification = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"\"}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, ""));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldIgnoreRecordWhenStatusIsNotAString() {
        Map<String, Object> notification = new HashMap<>();
        notification.put(Constants.STATUS, 42); // non-String status must not match "PENDING"
        notification.put(Constants.NOTIFICATION_ID, "notif-non-string-status");
        List<Map<String, Object>> records = List.of(notification);
        List<Map<String, Object>> result = validator.validateAndMarkExpired(records);
        assertEquals(records, result);
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldSkipMalformedEsDocumentsAndTreatMissingEndDatesAsNotExpired() throws Exception {
        Map<String, Object> notif3 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"form-003\"}");
        Map<String, Object> notif4 = mutableRecord(Constants.STATUS_PENDING, "{\"formId\":\"form-004\"}");

        when(objectMapper.readValue(eq("{\"formId\":\"form-003\"}"), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, "form-003"));
        when(objectMapper.readValue(eq("{\"formId\":\"form-004\"}"), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.FORM_ID, "form-004"));
        when(cacheManager.get(anyString())).thenReturn(Optional.empty());

        Map<String, Object> docMissingFormId = Map.of("someField", "x"); // FORM_ID key absent entirely
        Map<String, Object> docBlankFormId = Map.of(Constants.FORM_ID, ""); // blank FORM_ID
        Map<String, Object> docNoEndDateAtAll = Map.of(Constants.FORM_ID, "form-003"); // no endDate, no additionalProperties
        Map<String, Object> docAdditionalPropertiesWithoutEndDate = Map.of(
                Constants.FORM_ID, "form-004",
                Constants.ADDITIONAL_PROPERTIES, Map.of("other", "value")); // additionalProperties present but no endDate

        when(esClientService.searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList()))
                .thenReturn(List.of(docMissingFormId, docBlankFormId, docNoEndDateAtAll, docAdditionalPropertiesWithoutEndDate));

        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notif3, notif4));

        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        assertEquals(Constants.STATUS_PENDING, result.get(1).get(Constants.STATUS));
        verify(esClientService, times(1)).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldReturnNullFormIdWhenMessageDataFieldIsNotAList() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null, "{}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of());
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    @Test
    void shouldReturnNullFormIdWhenFirstMessageDataEntryIsNotAMap() throws Exception {
        Map<String, Object> notification = mutableNotificationRecord(Constants.STATUS_PENDING, null,
                "{\"data\":[\"not-a-map\"]}");
        when(objectMapper.readValue(anyString(), any(TypeReference.class)))
                .thenReturn(Map.of(Constants.DATA, List.of("not-a-map")));
        List<Map<String, Object>> result = validator.validateAndMarkExpired(List.of(notification));
        assertEquals(Constants.STATUS_PENDING, result.get(0).get(Constants.STATUS));
        verify(esClientService, never()).searchByTerms(anyString(), anyString(), anyList(), anyString(), anyList());
    }

    private Map<String, Object> mutableNotificationRecord(String status, String metadata, String message) {
        Map<String, Object> notification = new HashMap<>();
        notification.put(Constants.STATUS, status);
        notification.put(Constants.METADATA, metadata);
        notification.put(Constants.MESSAGE, message);
        notification.put(Constants.NOTIFICATION_ID, "notif-" + System.nanoTime());
        return notification;
    }
}
