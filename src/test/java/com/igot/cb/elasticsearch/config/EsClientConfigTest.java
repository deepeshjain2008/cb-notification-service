package com.igot.cb.elasticsearch.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.sun.net.httpserver.HttpServer;
import org.elasticsearch.client.Request;
import org.elasticsearch.client.Response;
import org.elasticsearch.client.RestClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class EsClientConfigTest {

    private EsClientConfig esClientConfig;

    @BeforeEach
    void setUp() {
        esClientConfig = new EsClientConfig();
        ReflectionTestUtils.setField(esClientConfig, "elasticsearchHosts", "localhost, 127.0.0.1");
        ReflectionTestUtils.setField(esClientConfig, "elasticsearchPort", 9200);
        ReflectionTestUtils.setField(esClientConfig, "elasticsearchUsername", "elastic");
        ReflectionTestUtils.setField(esClientConfig, "elasticsearchPassword", "password");
    }

    @Test
    void elasticsearchClient_shouldBuildClientWithConfiguredHostsAndCredentials() {
        ElasticsearchClient client = esClientConfig.elasticsearchClient();

        assertNotNull(client);
        assertNotNull(client._transport());
    }

    @Test
    void elasticsearchClient_singleHost_shouldStillBuildClient() {
        ReflectionTestUtils.setField(esClientConfig, "elasticsearchHosts", "localhost");

        ElasticsearchClient client = esClientConfig.elasticsearchClient();

        assertNotNull(client);
    }

    @Test
    void elasticsearchClient_realRequest_exercisesResponseInterceptor() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
        });
        server.start();
        try {
            ReflectionTestUtils.setField(esClientConfig, "elasticsearchHosts", "localhost");
            ReflectionTestUtils.setField(esClientConfig, "elasticsearchPort", server.getAddress().getPort());

            ElasticsearchClient client = esClientConfig.elasticsearchClient();
            RestClient restClient = ((RestClientTransport) client._transport()).restClient();

            Response response = restClient.performRequest(new Request("GET", "/"));

            assertEquals(200, response.getStatusLine().getStatusCode());
            assertEquals("Elasticsearch", response.getHeader("X-Elastic-Product"));
        } finally {
            server.stop(0);
        }
    }
}
