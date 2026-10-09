package com.igot.cb.peervalidationcleanup.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.igot.cb.util.CbServerProperties;
import com.igot.cb.util.Constants;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndTimestamp;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.record.TimestampType;
import org.igot.common.cassandra.CassandraOperation;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyMap;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link PeerValidationCleanupServiceImpl}.
 *
 * <p>Tests call {@code processCleanupEvents} directly (package-private) to validate
 * Cassandra deletion logic independently of Kafka infrastructure. Kafka-level tests
 * stub {@code createKafkaConsumer()} via a Mockito spy.
 */
@ExtendWith(MockitoExtension.class)
class PeerValidationCleanupServiceImplTest {

    @Mock
    private CbServerProperties cbServerProperties;
    @Mock
    private CassandraOperation cassandraOperation;

    private PeerValidationCleanupServiceImpl service;

    private static final LocalDate TARGET_DATE = LocalDate.of(2024, 6, 1);
    private static final String USER_ID = "user-001";
    private static final String NOTIFICATION_ID = "notif-001";
    private static final String CREATED_AT_ISO = "2024-06-01T10:00:00Z";
    private static final String TOPIC = "test.topic";

    @BeforeEach
    void setUp() {
        service = spy(new PeerValidationCleanupServiceImpl(cbServerProperties, cassandraOperation, new ObjectMapper()));
        lenient().when(cbServerProperties.getPeerReviewAssignedExcludedStatuses())
                .thenReturn(List.of(Constants.STATUS_APPROVED, Constants.STATUS_REJECTED));
        lenient().when(cbServerProperties.getCleanupBatchSize()).thenReturn(100);
        lenient().when(cbServerProperties.getCleanupThreadPoolSize()).thenReturn(2);
        // Table names — match the production defaults so assertions against Constants still hold
        lenient().when(cbServerProperties.getCleanupTableRequests())
                .thenReturn(Constants.TABLE_PEER_VALIDATION_REQUESTS);
        lenient().when(cbServerProperties.getCleanupTableReviews())
                .thenReturn(Constants.TABLE_PEER_VALIDATION_REVIEWS);
        lenient().when(cbServerProperties.getCleanupTableAudit())
                .thenReturn(Constants.TABLE_PEER_VALIDATION_CLEANUP_FAILURES);
        // Time-window and scheduling settings
        lenient().when(cbServerProperties.getCleanupWindowStartTime()).thenReturn("00:01:00");
        lenient().when(cbServerProperties.getCleanupWindowEndTime()).thenReturn("23:59:59");
        lenient().when(cbServerProperties.getCleanupDayOffset()).thenReturn(1L);
        lenient().when(cbServerProperties.getCleanupPollTimeoutSeconds()).thenReturn(5);
        lenient().when(cbServerProperties.getCleanupExecutorShutdownTimeoutMinutes()).thenReturn(10);
        lenient().when(cbServerProperties.getCleanupAuditDeletionPrefix()).thenReturn("DELETED: ");
    }

    @Nested
    @DisplayName("processCleanupEvents – validation failures")
    class ValidationFailures {

        @Test
        @DisplayName("empty list returns zero summary")
        void emptyList_returnsZeroSummary() {
            var summary = service.processCleanupEvents(List.of(), TARGET_DATE);
            assertThat(summary.eligible()).isZero();
            assertThat(summary.deleted()).isZero();
            assertThat(summary.failed()).isZero();
        }

        @Test
        @DisplayName("invalid JSON increments failed count but does not insert to Cassandra")
        void invalidJson_incrementsFailedCount() {
            var summary = service.processCleanupEvents(List.of("not-json{{{"), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.eligible()).isZero();
            verify(cassandraOperation, never()).insertRecord(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("missing userId increments failed count")
        void missingUserId_incrementsFailed() {
            String json = buildJson(null, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.eligible()).isZero();
        }

        @Test
        @DisplayName("blank notificationId increments failed count")
        void blankNotificationId_incrementsFailed() {
            String json = buildJson(USER_ID, "  ", CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
        }

        @Test
        @DisplayName("unknown subCategory increments failed count")
        void unknownSubCategory_incrementsFailed() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "UNKNOWN_CATEGORY", Constants.STATUS_SUBMITTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
        }

        @Test
        @DisplayName("invalid createdAt increments failed count")
        void invalidCreatedAt_incrementsFailed() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, "not-a-date", "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
        }

        @Test
        @DisplayName("PENDING status for PEER_REVIEW_ASSIGNED is not eligible for deletion")
        void pendingStatus_peerReviewAssigned_notEligible() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO,
                    "PEER_REVIEW_ASSIGNED", Constants.STATUS_PENDING);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.eligible()).isZero();
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("SUBMITTED status for PEER_REVIEW_ASSIGNED is not eligible for deletion")
        void submittedStatus_peerReviewAssigned_notEligible() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO,
                    "PEER_REVIEW_ASSIGNED", Constants.STATUS_SUBMITTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.eligible()).isZero();
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("null status for PEER_REVIEW_ASSIGNED is not eligible for deletion")
        void nullStatus_peerReviewAssigned_notEligible() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_REVIEW_ASSIGNED", null);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.eligible()).isZero();
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("processCleanupEvents – successful deletions")
    class SuccessfulDeletions {

        @Test
        @DisplayName("PEER_EVALUATION_ASSIGNED event deletes from requests table and notifications")
        void peerEvaluationAssigned_deletesCorrectTables() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.eligible()).isEqualTo(1);
            assertThat(summary.deleted()).isEqualTo(1);
            assertThat(summary.failed()).isZero();
            verify(cassandraOperation).deleteRecord(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_PEER_VALIDATION_REQUESTS,
                    Map.of(Constants.USER_ID, USER_ID, Constants.NOTIFICATION_ID, NOTIFICATION_ID)
            );
            verify(cassandraOperation, never()).deleteRecord(
                    eq(Constants.KEYSPACE_SUNBIRD),
                    eq(Constants.TABLE_USER_NOTIFICATION),
                    anyMap()
            );
        }

        @Test
        @DisplayName("PEER_REVIEW_ASSIGNED event routes to reviews table")
        void peerReviewAssigned_routesToReviewsTable() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_REVIEW_ASSIGNED", Constants.STATUS_APPROVED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.deleted()).isEqualTo(1);
            verify(cassandraOperation).deleteRecord(
                    Constants.KEYSPACE_SUNBIRD,
                    Constants.TABLE_PEER_VALIDATION_REVIEWS,
                    Map.of(Constants.USER_ID, USER_ID, Constants.NOTIFICATION_ID, NOTIFICATION_ID)
            );
        }

        @Test
        @DisplayName("REJECTED status for PEER_REVIEW_ASSIGNED is eligible for deletion")
        void rejectedStatus_peerReviewAssigned_isEligibleForDeletion() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_REVIEW_ASSIGNED", Constants.STATUS_REJECTED);
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.deleted()).isEqualTo(1);
            assertThat(summary.failed()).isZero();
            verify(cassandraOperation).deleteRecord(
                    eq(Constants.KEYSPACE_SUNBIRD),
                    eq(Constants.TABLE_PEER_VALIDATION_REVIEWS),
                    anyMap()
            );
        }

        @Test
        @DisplayName("multiple successful events produce a single bulk audit insert call")
        void multipleSuccessful_bulkAuditCalledOnce() {
            String eval = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            String review = buildJson("user-002", "notif-002", CREATED_AT_ISO, "PEER_REVIEW_ASSIGNED", Constants.STATUS_APPROVED);
            service.processCleanupEvents(List.of(eval, review), TARGET_DATE);
            verify(cassandraOperation, times(1)).insertBulkRecord(
                    eq(Constants.KEYSPACE_SUNBIRD),
                    eq(Constants.TABLE_PEER_VALIDATION_CLEANUP_FAILURES),
                    anyList()
            );
        }

        @Test
        @DisplayName("subCategory matching is case-insensitive")
        void subCategory_caseInsensitiveMatch() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "peer_evaluation_assigned", Constants.STATUS_SUBMITTED);

            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);

            assertThat(summary.deleted()).isEqualTo(1);
        }

        @Test
        @DisplayName("successful deletion inserts a bulk audit record into the cleanup audit table")
        void successfulDelete_insertsBulkAuditRecord() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            service.processCleanupEvents(List.of(json), TARGET_DATE);
            verify(cassandraOperation, times(1)).insertBulkRecord(
                    eq(Constants.KEYSPACE_SUNBIRD),
                    eq(Constants.TABLE_PEER_VALIDATION_CLEANUP_FAILURES),
                    anyList()
            );
        }

        @Test
        @DisplayName("multiple events produce correct aggregate counts")
        void multipleEvents_correctCounts() {
            String valid1 = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            String valid2 = buildJson("user-002", "notif-002", CREATED_AT_ISO, "PEER_REVIEW_ASSIGNED", Constants.STATUS_APPROVED);
            String invalid = "bad-json";

            var summary = service.processCleanupEvents(List.of(valid1, valid2, invalid), TARGET_DATE);

            assertThat(summary.eligible()).isEqualTo(2);
            assertThat(summary.deleted()).isEqualTo(2);
            assertThat(summary.failed()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("processCleanupEvents – Cassandra errors")
    class CassandraErrors {

        @Test
        @DisplayName("Cassandra deleteRecord throws increments failed count and skips audit insert")
        void cassandraThrows_incrementsFailedCountAndSkipsAudit() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            doThrow(new RuntimeException("Cassandra unavailable"))
                    .when(cassandraOperation).deleteRecord(anyString(), anyString(), anyMap());
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.eligible()).isEqualTo(1);
            assertThat(summary.failed()).isEqualTo(1);
            assertThat(summary.deleted()).isZero();
            verify(cassandraOperation, never()).insertBulkRecord(anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("Cassandra insertBulkRecord throws is swallowed with a warning and does not affect the summary")
        void insertBulkRecordThrows_isSwallowed() {
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            doThrow(new RuntimeException("Cassandra audit insert unavailable"))
                    .when(cassandraOperation).insertBulkRecord(anyString(), anyString(), anyList());
            var summary = service.processCleanupEvents(List.of(json), TARGET_DATE);
            assertThat(summary.eligible()).isEqualTo(1);
            assertThat(summary.deleted()).isEqualTo(1);
            assertThat(summary.failed()).isZero();
            verify(cassandraOperation, times(1)).insertBulkRecord(anyString(), anyString(), anyList());
        }
    }

    @Nested
    @DisplayName("runCleanup – orchestration")
    class RunCleanup {

        @Mock
        private KafkaConsumer<String, String> mockConsumer;

        private PeerValidationCleanupServiceImpl kafkaService;

        @BeforeEach
        void setUpKafkaService() {
            kafkaService = spy(new PeerValidationCleanupServiceImpl(
                    cbServerProperties, cassandraOperation, new ObjectMapper()));
            doReturn(mockConsumer).when(kafkaService).createKafkaConsumer();
        }

        @AfterEach
        void closeMockConsumer() {
            mockConsumer.close();
        }

        @Test
        @DisplayName("no partitions for topic → zero events processed, no Cassandra calls")
        void noPartitions_zeroEvents() {
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(Collections.emptyList());
            kafkaService.runCleanup(Instant.now());
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("null partitions list for topic → zero events processed, no Cassandra calls")
        void nullPartitions_zeroEvents() {
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(null);
            kafkaService.runCleanup(Instant.now());
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("consumer returns empty poll → no Cassandra calls")
        void emptyPoll_noCassandraCalls() {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PartitionInfo pi = new PartitionInfo(TOPIC, 0, null, null, null);
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(List.of(pi));
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, 0L)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 0L));
            kafkaService.runCleanup(Instant.now());
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }

        @Test
        @DisplayName("single valid event in window → one Cassandra delete pair")
        void singleEventInWindow_deletesCassandraRows() {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PartitionInfo pi = new PartitionInfo(TOPIC, 0, null, null, null);
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            long windowStartMs = yesterday.atTime(0, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
            long windowEndMs = yesterday.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
            long midpointMs = (windowStartMs + windowEndMs) / 2;
            ConsumerRecord<String, String> kafkaRecord =
                    new ConsumerRecord<>(TOPIC, 0, 0L, midpointMs,
                            TimestampType.CREATE_TIME, -1L, -1, -1, null, json);
            ConsumerRecords<String, String> batch =
                    new ConsumerRecords<>(Map.of(tp, List.of(kafkaRecord)));
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(List.of(pi));
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, windowStartMs)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 2L));
            doReturn(batch).doReturn(ConsumerRecords.<String, String>empty()).when(mockConsumer).poll(any(Duration.class));
            kafkaService.runCleanup(Instant.now());
            verify(cassandraOperation, times(1)).deleteRecord(
                    eq(Constants.KEYSPACE_SUNBIRD),
                    eq(Constants.TABLE_PEER_VALIDATION_REQUESTS),
                    anyMap()
            );
            verify(cassandraOperation, never()).deleteRecord(
                    eq(Constants.KEYSPACE_SUNBIRD),
                    eq(Constants.TABLE_USER_NOTIFICATION),
                    anyMap()
            );
        }
    }

    @Nested
    @DisplayName("parseTopics – configuration edge cases (via runCleanup)")
    class ParseTopics {

        @Mock
        private KafkaConsumer<String, String> mockConsumer;

        private PeerValidationCleanupServiceImpl kafkaService;

        @BeforeEach
        void setUpKafkaService() {
            kafkaService = spy(new PeerValidationCleanupServiceImpl(
                    cbServerProperties, cassandraOperation, new ObjectMapper()));
            doReturn(mockConsumer).when(kafkaService).createKafkaConsumer();
        }

        @AfterEach
        void closeMockConsumer() {
            mockConsumer.close();
        }

        @Test
        @DisplayName("topics with blank entries are filtered out")
        void blankTopicEntries_filtered() {
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn("topic-a,,  ,topic-b");
            when(mockConsumer.partitionsFor("topic-a")).thenReturn(Collections.emptyList());
            when(mockConsumer.partitionsFor("topic-b")).thenReturn(Collections.emptyList());
            kafkaService.runCleanup(Instant.now());
            verify(mockConsumer).partitionsFor("topic-a");
            verify(mockConsumer).partitionsFor("topic-b");
        }
    }

    @Nested
    @DisplayName("processRecord – window boundary decisions")
    class ProcessRecord {

        @Mock
        private KafkaConsumer<String, String> mockConsumer;

        private PeerValidationCleanupServiceImpl kafkaService;

        @BeforeEach
        void setUpKafkaService() {
            kafkaService = spy(new PeerValidationCleanupServiceImpl(
                    cbServerProperties, cassandraOperation, new ObjectMapper()));
            doReturn(mockConsumer).when(kafkaService).createKafkaConsumer();
        }

        @AfterEach
        void closeMockConsumer() {
            mockConsumer.close();
        }

        @Test
        @DisplayName("record after window end marks partition exhausted, not collected")
        void recordAfterWindowEnd_exhausted_notCollected() {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PartitionInfo pi = new PartitionInfo(TOPIC, 0, null, null, null);
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            long windowEndMs = yesterday.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
            long afterWindowMs = windowEndMs + 10_000;
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            ConsumerRecord<String, String> lateRecord =
                    new ConsumerRecord<>(TOPIC, 0, 0L, afterWindowMs,
                            TimestampType.CREATE_TIME, -1L, -1, -1, null, json);
            ConsumerRecords<String, String> batch =
                    new ConsumerRecords<>(Map.of(tp, List.of(lateRecord)));
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(List.of(pi));
            long windowStartMs = yesterday.atTime(0, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, windowStartMs)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 5L));
            doReturn(batch).doReturn(ConsumerRecords.<String, String>empty()).when(mockConsumer).poll(any(Duration.class));
            kafkaService.runCleanup(Instant.now());
            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
        }
    }

    @Nested
    @DisplayName("CleanupEvent record accessors")
    class CleanupEventRecordTests {

        @Test
        @DisplayName("createdAt() returns the constructed Instant")
        void createdAt_returnsConstructedInstant() {
            Instant createdAt = Instant.parse(CREATED_AT_ISO);
            PeerValidationCleanupServiceImpl.CleanupEvent event =
                    new PeerValidationCleanupServiceImpl.CleanupEvent(
                            USER_ID, NOTIFICATION_ID, createdAt, Constants.TABLE_PEER_VALIDATION_REQUESTS);
            assertThat(event.createdAt()).isEqualTo(createdAt);
        }
    }

    @Nested
    @DisplayName("shutdownExecutor – timeout and interrupt branches")
    class ShutdownExecutorBranches {

        @Test
        @DisplayName("executor not terminated within timeout logs a warning without throwing")
        void notTerminatedWithinTimeout_logsWarning() throws Exception {
            when(cbServerProperties.getCleanupExecutorShutdownTimeoutMinutes()).thenReturn(0);
            ExecutorService executor = Executors.newFixedThreadPool(1);
            executor.submit(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
            });
            try {
                invokePrivate(service, "shutdownExecutor",
                        new Class<?>[]{ExecutorService.class}, executor);
            } finally {
                executor.shutdownNow();
            }
        }

        @Test
        @DisplayName("interrupted while awaiting termination re-interrupts the thread and logs a warning")
        void interruptedWhileAwaitingTermination_logsWarning() throws Exception {
            when(cbServerProperties.getCleanupExecutorShutdownTimeoutMinutes()).thenReturn(1);
            ExecutorService executor = Executors.newFixedThreadPool(1);
            executor.submit(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                }
            });
            Thread.currentThread().interrupt();
            try {
                invokePrivate(service, "shutdownExecutor",
                        new Class<?>[]{ExecutorService.class}, executor);
                assertThat(Thread.interrupted()).isTrue();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Nested
    @DisplayName("invokeAndCollectAuditMaps – interrupted and exception handling")
    class InvokeAndCollectAuditMapsBranches {

        @Test
        @DisplayName("executor.invokeAll interrupted propagates through the outer catch and returns an empty list")
        void invokeAllInterrupted_returnsEmptyList() throws Exception {
            ExecutorService mockExecutor = mock(ExecutorService.class);
            when(mockExecutor.invokeAll(anyList())).thenThrow(new InterruptedException("boom"));

            List<Callable<Map<String, Object>>> tasks = List.of(() -> Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) invokePrivate(
                    service, "invokeAndCollectAuditMaps",
                    new Class<?>[]{List.class, ExecutorService.class}, tasks, mockExecutor);

            assertThat(result).isEmpty();
            assertThat(Thread.interrupted()).isTrue();
        }

        @Test
        @DisplayName("per-future InterruptedException and generic Exception are both swallowed; only non-empty maps survive the filter")
        void perFutureInterruptedAndGenericException_filteredOut() throws Exception {
            ExecutorService mockExecutor = mock(ExecutorService.class);

            @SuppressWarnings("unchecked")
            Future<Map<String, Object>> interruptedFuture = mock(Future.class);
            when(interruptedFuture.get()).thenThrow(new InterruptedException("boom"));

            @SuppressWarnings("unchecked")
            Future<Map<String, Object>> failingFuture = mock(Future.class);
            when(failingFuture.get()).thenThrow(new ExecutionException("boom", new RuntimeException()));

            @SuppressWarnings("unchecked")
            Future<Map<String, Object>> successfulFuture = mock(Future.class);
            when(successfulFuture.get()).thenReturn(Map.of(Constants.USER_ID, USER_ID));

            doReturn(List.of(interruptedFuture, failingFuture, successfulFuture))
                    .when(mockExecutor).invokeAll(anyList());

            List<Callable<Map<String, Object>>> tasks = List.of(() -> Map.of(), () -> Map.of(), () -> Map.of());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> result = (List<Map<String, Object>>) invokePrivate(
                    service, "invokeAndCollectAuditMaps",
                    new Class<?>[]{List.class, ExecutorService.class}, tasks, mockExecutor);

            assertThat(result).hasSize(1);
            assertThat(Thread.interrupted()).isTrue();
        }
    }

    @Nested
    @DisplayName("resolveActivePartitions – offsetsForTimes / endOffsets combinations")
    class ResolveActivePartitionsBranches {

        @Mock
        private KafkaConsumer<String, String> mockConsumer;

        private PeerValidationCleanupServiceImpl plainService;

        @BeforeEach
        void setUpPlainService() {
            plainService = new PeerValidationCleanupServiceImpl(cbServerProperties, cassandraOperation, new ObjectMapper());
        }

        @Test
        @DisplayName("null OffsetAndTimestamp excludes the partition")
        void nullOffsetAndTimestamp_excluded() throws Exception {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            when(mockConsumer.offsetsForTimes(anyMap())).thenReturn(Map.of());
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 5L));

            @SuppressWarnings("unchecked")
            Set<TopicPartition> active = (Set<TopicPartition>) invokePrivate(
                    plainService, "resolveActivePartitions",
                    new Class<?>[]{KafkaConsumer.class, List.class, long.class},
                    mockConsumer, List.of(tp), 1000L);

            assertThat(active).isEmpty();
        }

        @Test
        @DisplayName("null end offset excludes the partition")
        void nullEndOffset_excluded() throws Exception {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, 0L)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of());

            @SuppressWarnings("unchecked")
            Set<TopicPartition> active = (Set<TopicPartition>) invokePrivate(
                    plainService, "resolveActivePartitions",
                    new Class<?>[]{KafkaConsumer.class, List.class, long.class},
                    mockConsumer, List.of(tp), 1000L);

            assertThat(active).isEmpty();
        }
    }

    @Nested
    @DisplayName("processRecord – window boundary and offset-exhaustion branches")
    class ProcessRecordBranches {

        @Test
        @DisplayName("record before window start is skipped but not marked exhausted")
        void recordBeforeWindowStart_skippedNotExhausted() throws Exception {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PeerValidationCleanupServiceImpl.TimeWindow window =
                    new PeerValidationCleanupServiceImpl.TimeWindow(1_000_000L, 2_000_000L);
            ConsumerRecord<String, String> kafkaRecord = new ConsumerRecord<>(
                    TOPIC, 0, 0L, 500_000L, TimestampType.CREATE_TIME, -1L, -1, -1, null, "payload");
            Map<TopicPartition, Long> endOffsets = Map.of(tp, 10L);
            Set<TopicPartition> exhausted = new HashSet<>();
            List<String> collected = new ArrayList<>();

            invokePrivate(service, "processRecord",
                    new Class<?>[]{ConsumerRecord.class, Map.class, PeerValidationCleanupServiceImpl.TimeWindow.class, Consumer.class, Set.class},
                    kafkaRecord, endOffsets, window, (Consumer<String>) collected::add, exhausted);

            assertThat(collected).isEmpty();
            assertThat(exhausted).isEmpty();
        }

        @Test
        @DisplayName("record within window but missing end offset is not marked exhausted")
        void recordWithinWindow_missingEndOffset_notExhausted() throws Exception {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PeerValidationCleanupServiceImpl.TimeWindow window =
                    new PeerValidationCleanupServiceImpl.TimeWindow(1_000_000L, 2_000_000L);
            ConsumerRecord<String, String> kafkaRecord = new ConsumerRecord<>(
                    TOPIC, 0, 0L, 1_500_000L, TimestampType.CREATE_TIME, -1L, -1, -1, null, "payload");
            Map<TopicPartition, Long> endOffsets = Map.of();
            Set<TopicPartition> exhausted = new HashSet<>();
            List<String> collected = new ArrayList<>();

            invokePrivate(service, "processRecord",
                    new Class<?>[]{ConsumerRecord.class, Map.class, PeerValidationCleanupServiceImpl.TimeWindow.class, Consumer.class, Set.class},
                    kafkaRecord, endOffsets, window, (Consumer<String>) collected::add, exhausted);

            assertThat(collected).containsExactly("payload");
            assertThat(exhausted).isEmpty();
        }

        @Test
        @DisplayName("record offset well below the log-end offset is not marked exhausted")
        void recordOffsetBelowEndOffset_notExhausted() throws Exception {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PeerValidationCleanupServiceImpl.TimeWindow window =
                    new PeerValidationCleanupServiceImpl.TimeWindow(1_000_000L, 2_000_000L);
            ConsumerRecord<String, String> kafkaRecord = new ConsumerRecord<>(
                    TOPIC, 0, 0L, 1_500_000L, TimestampType.CREATE_TIME, -1L, -1, -1, null, "payload");
            Map<TopicPartition, Long> endOffsets = Map.of(tp, 100L);
            Set<TopicPartition> exhausted = new HashSet<>();
            List<String> collected = new ArrayList<>();

            invokePrivate(service, "processRecord",
                    new Class<?>[]{ConsumerRecord.class, Map.class, PeerValidationCleanupServiceImpl.TimeWindow.class, Consumer.class, Set.class},
                    kafkaRecord, endOffsets, window, (Consumer<String>) collected::add, exhausted);

            assertThat(collected).containsExactly("payload");
            assertThat(exhausted).isEmpty();
        }

        @Test
        @DisplayName("record at the log-end offset is marked exhausted")
        void recordAtLogEndOffset_markedExhausted() throws Exception {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PeerValidationCleanupServiceImpl.TimeWindow window =
                    new PeerValidationCleanupServiceImpl.TimeWindow(1_000_000L, 2_000_000L);
            ConsumerRecord<String, String> kafkaRecord = new ConsumerRecord<>(
                    TOPIC, 0, 4L, 1_500_000L, TimestampType.CREATE_TIME, -1L, -1, -1, null, "payload");
            Map<TopicPartition, Long> endOffsets = Map.of(tp, 5L);
            Set<TopicPartition> exhausted = new HashSet<>();
            List<String> collected = new ArrayList<>();

            invokePrivate(service, "processRecord",
                    new Class<?>[]{ConsumerRecord.class, Map.class, PeerValidationCleanupServiceImpl.TimeWindow.class, Consumer.class, Set.class},
                    kafkaRecord, endOffsets, window, (Consumer<String>) collected::add, exhausted);

            assertThat(collected).containsExactly("payload");
            assertThat(exhausted).containsExactly(tp);
        }
    }

    @Nested
    @DisplayName("resolveActionTable – defensive null guard")
    class ResolveActionTableBranches {

        @Test
        @DisplayName("returns null for a null subCategory")
        void nullSubCategory_returnsNull() throws Exception {
            Object result = invokePrivate(service, "resolveActionTable", new Class<?>[]{String.class}, (Object) null);
            assertThat(result).isNull();
        }
    }

    @Nested
    @DisplayName("createKafkaConsumer – real consumer construction")
    class CreateKafkaConsumerTests {

        @Test
        @DisplayName("builds a consumer from configured properties without connecting to a broker")
        void buildsConfiguredConsumer() {
            when(cbServerProperties.getSpringKafkaBootStrapServers()).thenReturn("localhost:9092");
            when(cbServerProperties.getCleanupConsumerGroupId()).thenReturn("peer-validation-cleanup-test");

            KafkaConsumer<String, String> consumer = service.createKafkaConsumer();
            try {
                assertThat(consumer).isNotNull();
            } finally {
                consumer.close();
            }
        }
    }

    @Nested
    @DisplayName("runCleanup – chunk flushing and parallel-path edge cases")
    class ChunkFlushingAndParallelPath {

        @Mock
        private KafkaConsumer<String, String> mockConsumer;

        private PeerValidationCleanupServiceImpl kafkaService;

        @BeforeEach
        void setUpKafkaService() {
            kafkaService = spy(new PeerValidationCleanupServiceImpl(
                    cbServerProperties, cassandraOperation, new ObjectMapper()));
            doReturn(mockConsumer).when(kafkaService).createKafkaConsumer();
        }

        @AfterEach
        void closeMockConsumer() {
            mockConsumer.close();
        }

        @Test
        @DisplayName("chunk size of 1 flushes inline for each event, producing one bulk audit insert per event")
        void chunkSizeOne_flushesInline() {
            when(cbServerProperties.getCleanupBatchSize()).thenReturn(1);
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PartitionInfo pi = new PartitionInfo(TOPIC, 0, null, null, null);
            String json1 = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            String json2 = buildJson("user-002", "notif-002", CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            long windowStartMs = yesterday.atTime(0, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
            long windowEndMs = yesterday.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
            long midpointMs = (windowStartMs + windowEndMs) / 2;
            ConsumerRecord<String, String> record1 = new ConsumerRecord<>(
                    TOPIC, 0, 0L, midpointMs, TimestampType.CREATE_TIME, -1L, -1, -1, null, json1);
            ConsumerRecord<String, String> record2 = new ConsumerRecord<>(
                    TOPIC, 0, 1L, midpointMs, TimestampType.CREATE_TIME, -1L, -1, -1, null, json2);
            ConsumerRecords<String, String> batch =
                    new ConsumerRecords<>(Map.of(tp, List.of(record1, record2)));
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(List.of(pi));
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, windowStartMs)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 5L));
            doReturn(batch).doReturn(ConsumerRecords.<String, String>empty()).when(mockConsumer).poll(any(Duration.class));

            kafkaService.runCleanup(Instant.now());

            verify(cassandraOperation, times(2)).deleteRecord(anyString(), anyString(), anyMap());
            verify(cassandraOperation, times(2)).insertBulkRecord(anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("invalid event in the Kafka parallel path increments failed count and skips the audit insert")
        void invalidEventInParallelPath_skipsAudit() {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PartitionInfo pi = new PartitionInfo(TOPIC, 0, null, null, null);
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            long windowStartMs = yesterday.atTime(0, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
            long windowEndMs = yesterday.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
            long midpointMs = (windowStartMs + windowEndMs) / 2;
            ConsumerRecord<String, String> badRecord = new ConsumerRecord<>(
                    TOPIC, 0, 0L, midpointMs, TimestampType.CREATE_TIME, -1L, -1, -1, null, "not-json{{{");
            ConsumerRecords<String, String> batch =
                    new ConsumerRecords<>(Map.of(tp, List.of(badRecord)));
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(List.of(pi));
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, windowStartMs)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 5L));
            doReturn(batch).doReturn(ConsumerRecords.<String, String>empty()).when(mockConsumer).poll(any(Duration.class));

            kafkaService.runCleanup(Instant.now());

            verify(cassandraOperation, never()).deleteRecord(anyString(), anyString(), anyMap());
            verify(cassandraOperation, never()).insertBulkRecord(anyString(), anyString(), anyList());
        }

        @Test
        @DisplayName("Cassandra delete failure in the parallel path increments failed count and skips the audit insert")
        void cassandraDeleteFailsInParallelPath_skipsAudit() {
            TopicPartition tp = new TopicPartition(TOPIC, 0);
            PartitionInfo pi = new PartitionInfo(TOPIC, 0, null, null, null);
            String json = buildJson(USER_ID, NOTIFICATION_ID, CREATED_AT_ISO, "PEER_EVALUATION_ASSIGNED", Constants.STATUS_SUBMITTED);
            LocalDate yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1);
            long windowStartMs = yesterday.atTime(0, 1, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
            long windowEndMs = yesterday.atTime(23, 59, 59).toInstant(ZoneOffset.UTC).toEpochMilli();
            long midpointMs = (windowStartMs + windowEndMs) / 2;
            ConsumerRecord<String, String> record = new ConsumerRecord<>(
                    TOPIC, 0, 0L, midpointMs, TimestampType.CREATE_TIME, -1L, -1, -1, null, json);
            ConsumerRecords<String, String> batch =
                    new ConsumerRecords<>(Map.of(tp, List.of(record)));
            when(cbServerProperties.getCleanupKafkaTopics()).thenReturn(TOPIC);
            when(mockConsumer.partitionsFor(TOPIC)).thenReturn(List.of(pi));
            when(mockConsumer.offsetsForTimes(anyMap()))
                    .thenReturn(Map.of(tp, new OffsetAndTimestamp(0L, windowStartMs)));
            when(mockConsumer.endOffsets(anyList())).thenReturn(Map.of(tp, 5L));
            doReturn(batch).doReturn(ConsumerRecords.<String, String>empty()).when(mockConsumer).poll(any(Duration.class));
            doThrow(new RuntimeException("Cassandra unavailable"))
                    .when(cassandraOperation).deleteRecord(anyString(), anyString(), anyMap());

            kafkaService.runCleanup(Instant.now());

            verify(cassandraOperation, times(1)).deleteRecord(anyString(), anyString(), anyMap());
            verify(cassandraOperation, never()).insertBulkRecord(anyString(), anyString(), anyList());
        }
    }

    /**
     * Invokes a private method on {@code target} via reflection, to exercise branches
     * (e.g. defensive null-guards, concurrency edge cases) that are only reachable by
     * calling the helper method directly rather than through its public entry points.
     */
    private static Object invokePrivate(Object target, String methodName, Class<?>[] paramTypes, Object... args) throws Exception {
        Method method = PeerValidationCleanupServiceImpl.class.getDeclaredMethod(methodName, paramTypes);
        method.setAccessible(true);
        return method.invoke(target, args);
    }

    private String buildJson(String userId, String notificationId, String createdAt,
                             String subCategory, String status) {
        Map<String, Object> map = new HashMap<>();
        if (userId != null) map.put(Constants.USER_ID_FIELD, userId);
        if (notificationId != null) map.put(Constants.NOTIFICATION_ID_FIELD, notificationId);
        if (createdAt != null) map.put(Constants.CREATED_AT_FIELD, createdAt);
        if (subCategory != null) map.put(Constants.SUB_CATEGORY_FIELD, subCategory);
        if (status != null) map.put(Constants.STATUS_FIELD, status);
        try {
            return new ObjectMapper().writeValueAsString(map);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
