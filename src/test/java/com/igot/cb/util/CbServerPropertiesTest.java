package com.igot.cb.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CbServerPropertiesTest {

    @Test
    void testAllGettersAndSetters() {
        CbServerProperties props = new CbServerProperties();

        props.setRedisInsightIndex(1);
        props.setSearchResultRedisTtl(1000L);
        props.setSbApiKey("dummy-api-key");
        props.setRequestTimeoutMs(5000);
        props.setMaxTotalConnections(100);
        props.setMaxConnectionsPerRoute(20);
        props.setRedisPoolMaxTotal(50);
        props.setRedisPoolMaxIdle(25);
        props.setRedisPoolMinIdle(5);
        props.setRedisPoolMaxWait(3000);
        props.setRedisConnectionTimeout(2000L);
        props.setSpringKafkaBootStrapServers("localhost:9092");
        props.setPeerValidationBulkUserNotificationLimit(10);
        props.setPeerValidationNotificationSettingCheckEnabled(true);
        props.setPeerValidationListMaxFetch(50);
        props.setKafkaTopicPeerValidationError("peer-validation-error");
        props.setKafkaTopicNotificationReadEvent("notification-read-event");
        props.setPeerValidationBulkCreatedAtOffsetMs(500L);
        props.setKafkaTopicPeerEvaluationError("peer-evaluation-error");
        props.setKafkaTopicNotificationBulkCreateError("notification-bulk-create-error");
        props.setPeerEvaluationAssignedExcludedStatuses(List.of("ASSIGNED", "COMPLETED"));
        props.setPeerReviewAssignedExcludedStatuses(List.of("PENDING"));
        props.setCleanupConsumerGroupId("cleanup-group");
        props.setCleanupKafkaTopics("cleanup-topic");
        props.setCleanupBatchSize(100);
        props.setCleanupThreadPoolSize(4);
        props.setCleanupTableRequests("requests-table");
        props.setCleanupTableReviews("reviews-table");
        props.setCleanupTableAudit("audit-table");
        props.setCleanupWindowStartTime("00:00");
        props.setCleanupWindowEndTime("05:00");
        props.setCleanupDayOffset(7L);
        props.setCleanupPollTimeoutSeconds(30);
        props.setCleanupExecutorShutdownTimeoutMinutes(5);
        props.setCleanupAuditDeletionPrefix("audit-");
        props.setMandatoryNotificationMaxFetchLimit(100);
        props.setFormsEsIndexAlias("forms-index");
        props.setFormsEsContextType("survey");
        props.setFormsEsFetchFields(List.of("formId", "status"));
        props.setFormsEsFetchBatchSize(25);
        props.setFormsCacheTtlSeconds(600L);
        props.setFormsCacheMaxSize(1000L);

        assertEquals(1, props.getRedisInsightIndex());
        assertEquals(1000L, props.getSearchResultRedisTtl());
        assertEquals("dummy-api-key", props.getSbApiKey());
        assertEquals(5000, props.getRequestTimeoutMs());
        assertEquals(100, props.getMaxTotalConnections());
        assertEquals(20, props.getMaxConnectionsPerRoute());
        assertEquals(50, props.getRedisPoolMaxTotal());
        assertEquals(25, props.getRedisPoolMaxIdle());
        assertEquals(5, props.getRedisPoolMinIdle());
        assertEquals(3000, props.getRedisPoolMaxWait());
        assertEquals(2000L, props.getRedisConnectionTimeout());
        assertEquals("localhost:9092", props.getSpringKafkaBootStrapServers());
        assertEquals(10, props.getPeerValidationBulkUserNotificationLimit());
        assertTrue(props.isPeerValidationNotificationSettingCheckEnabled());
        assertEquals(50, props.getPeerValidationListMaxFetch());
        assertEquals("peer-validation-error", props.getKafkaTopicPeerValidationError());
        assertEquals("notification-read-event", props.getKafkaTopicNotificationReadEvent());
        assertEquals(500L, props.getPeerValidationBulkCreatedAtOffsetMs());
        assertEquals("peer-evaluation-error", props.getKafkaTopicPeerEvaluationError());
        assertEquals("notification-bulk-create-error", props.getKafkaTopicNotificationBulkCreateError());
        assertEquals(List.of("ASSIGNED", "COMPLETED"), props.getPeerEvaluationAssignedExcludedStatuses());
        assertEquals(List.of("PENDING"), props.getPeerReviewAssignedExcludedStatuses());
        assertEquals("cleanup-group", props.getCleanupConsumerGroupId());
        assertEquals("cleanup-topic", props.getCleanupKafkaTopics());
        assertEquals(100, props.getCleanupBatchSize());
        assertEquals(4, props.getCleanupThreadPoolSize());
        assertEquals("requests-table", props.getCleanupTableRequests());
        assertEquals("reviews-table", props.getCleanupTableReviews());
        assertEquals("audit-table", props.getCleanupTableAudit());
        assertEquals("00:00", props.getCleanupWindowStartTime());
        assertEquals("05:00", props.getCleanupWindowEndTime());
        assertEquals(7L, props.getCleanupDayOffset());
        assertEquals(30, props.getCleanupPollTimeoutSeconds());
        assertEquals(5, props.getCleanupExecutorShutdownTimeoutMinutes());
        assertEquals("audit-", props.getCleanupAuditDeletionPrefix());
        assertEquals(100, props.getMandatoryNotificationMaxFetchLimit());
        assertEquals("forms-index", props.getFormsEsIndexAlias());
        assertEquals("survey", props.getFormsEsContextType());
        assertEquals(List.of("formId", "status"), props.getFormsEsFetchFields());
        assertEquals(25, props.getFormsEsFetchBatchSize());
        assertEquals(600L, props.getFormsCacheTtlSeconds());
        assertEquals(1000L, props.getFormsCacheMaxSize());
    }

    @Test
    void testFalseBooleanFlag() {
        CbServerProperties props = new CbServerProperties();
        props.setPeerValidationNotificationSettingCheckEnabled(false);
        assertFalse(props.isPeerValidationNotificationSettingCheckEnabled());
    }
}
