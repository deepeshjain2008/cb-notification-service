package com.igot.cb.elasticsearch.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.elasticsearch.core.search.HitsMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EsClientServiceImplTest {

    @Mock
    private ElasticsearchClient elasticsearchClient;

    private EsClientServiceImpl esClientService;

    private static final String INDEX = "forms-index";
    private static final String FIELD = "formId";
    private static final String CONTEXT_TYPE = "survey";
    private static final int MAX_TERMS = 100;
    private static final String TIMEOUT = "5s";

    @BeforeEach
    void setUp() {
        esClientService = new EsClientServiceImpl(elasticsearchClient);
        ReflectionTestUtils.setField(esClientService, "maxTermsCount", MAX_TERMS);
        ReflectionTestUtils.setField(esClientService, "queryTimeout", TIMEOUT);
    }

    @Test
    void shouldReturnEmptyListWhenIndexNameIsBlank() throws IOException {
        List<Map<String, Object>> result = esClientService.searchByTerms("", FIELD, List.of("v1"), CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any());
    }

    @Test
    void shouldReturnEmptyListWhenIndexNameIsNull() throws IOException {
        List<Map<String, Object>> result = esClientService.searchByTerms(null, FIELD, List.of("v1"), CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any());
    }

    @Test
    void shouldReturnEmptyListWhenFieldNameIsBlank() throws IOException {
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, "", List.of("v1"), CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any());
    }

    @Test
    void shouldReturnEmptyListWhenValuesIsNull() throws IOException {
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, null, CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any());
    }

    @Test
    void shouldReturnEmptyListWhenValuesIsEmpty() throws IOException {
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, Collections.emptyList(), CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any());
    }

    @Test
    void shouldReturnEmptyListWhenValuesExceedMaxTermsCount() throws IOException {
        List<String> oversizedValues = Collections.nCopies(MAX_TERMS + 1, "formId");
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, oversizedValues, CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
        verify(elasticsearchClient, never()).search(any(SearchRequest.class), any());
    }

    @Test
    void shouldReturnDocumentsOnSuccessfulSearch() throws IOException {
        Map<String, Object> sourceDoc = Map.of("formId", "form-001", "endDate", 123456789L);
        mockEsResponse(List.of(sourceDoc));
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, List.of("form-001"), CONTEXT_TYPE, null);
        assertEquals(1, result.size());
        assertEquals("form-001", result.get(0).get("formId"));
    }

    @Test
    void shouldReturnEmptyListWhenEsThrowsIoException() throws IOException {
        when(elasticsearchClient.search(any(SearchRequest.class), any())).thenThrow(new IOException("ES down"));
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, List.of("v1"), CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldFilterOutNullSourceHits() throws IOException {
        mockEsResponseWithNullSource();
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, List.of("v1"), CONTEXT_TYPE, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void shouldWorkWithBlankContextType() throws IOException {
        Map<String, Object> sourceDoc = Map.of("formId", "form-001");
        mockEsResponse(List.of(sourceDoc));
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, List.of("form-001"), "", null);
        assertEquals(1, result.size());
    }

    @Test
    void shouldWorkWithNullContextType() throws IOException {
        Map<String, Object> sourceDoc = Map.of("formId", "form-001");
        mockEsResponse(List.of(sourceDoc));
        List<Map<String, Object>> result = esClientService.searchByTerms(INDEX, FIELD, List.of("form-001"), null, null);
        assertEquals(1, result.size());
    }

    @Test
    void shouldReturnMultipleDocumentsWhenMultipleHitsExist() throws IOException {
        List<Map<String, Object>> docs = List.of(
                Map.of("formId", "form-001"),
                Map.of("formId", "form-002"),
                Map.of("formId", "form-003"));
        mockEsResponse(docs);
        List<Map<String, Object>> result = esClientService.searchByTerms(
                INDEX, FIELD, List.of("form-001", "form-002", "form-003"), CONTEXT_TYPE, null);
        assertEquals(3, result.size());
    }

    @Test
    void shouldApplySourceFieldFilterWhenFieldsProvided() throws IOException {
        Map<String, Object> sourceDoc = Map.of("formId", "form-001");
        mockEsResponse(List.of(sourceDoc));
        List<Map<String, Object>> result = esClientService.searchByTerms(
                INDEX, FIELD, List.of("form-001"), CONTEXT_TYPE, List.of("formId", "status"));
        assertEquals(1, result.size());
    }

    private void mockEsResponse(List<Map<String, Object>> sources) throws IOException {
        SearchResponse<Map<String, Object>> response = mock(SearchResponse.class);
        HitsMetadata<Map<String, Object>> hitsMetadata = mock(HitsMetadata.class);
        List<Hit<Map<String, Object>>> hits = sources.stream()
                .map(src -> {
                    Hit<Map<String, Object>> hit = mock(Hit.class);
                    when(hit.source()).thenReturn(src);
                    return hit;
                })
                .toList();
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(hits);
        doReturn(response).when(elasticsearchClient).search(any(SearchRequest.class), any());
    }

    private void mockEsResponseWithNullSource() throws IOException {
        SearchResponse<Map<String, Object>> response = mock(SearchResponse.class);
        HitsMetadata<Map<String, Object>> hitsMetadata = mock(HitsMetadata.class);
        Hit<Map<String, Object>> hit = mock(Hit.class);
        when(hit.source()).thenReturn(null);
        when(response.hits()).thenReturn(hitsMetadata);
        when(hitsMetadata.hits()).thenReturn(List.of(hit));
        doReturn(response).when(elasticsearchClient).search(any(SearchRequest.class), any());
    }
}
