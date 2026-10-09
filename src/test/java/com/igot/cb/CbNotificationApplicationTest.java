package com.igot.cb;

import org.apache.hc.client5.http.classic.HttpClient;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class CbNotificationApplicationTest {

    @Test
    void testMain_delegatesToSpringApplicationRun() {
        String[] args = {"--server.port=8080"};
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

        try (MockedStatic<SpringApplication> mockedSpringApplication = mockStatic(SpringApplication.class)) {
            mockedSpringApplication.when(() -> SpringApplication.run(CbNotificationApplication.class, args))
                    .thenReturn(context);

            CbNotificationApplication.main(args);

            mockedSpringApplication.verify(() -> SpringApplication.run(CbNotificationApplication.class, args));
        }
    }

    @Test
    void testRestTemplateBeanAndClientHttpRequestFactory() {
        // Given
        CbNotificationApplication app = new CbNotificationApplication();

        // When
        RestTemplate restTemplate = app.restTemplate();

        // Then
        assertNotNull(restTemplate, "RestTemplate should not be null");
        ClientHttpRequestFactory factory = restTemplate.getRequestFactory();
        assertTrue(factory instanceof HttpComponentsClientHttpRequestFactory,
                "RequestFactory should be instance of HttpComponentsClientHttpRequestFactory");

        // Optional: Validate timeout setting and connection manager indirectly
        HttpComponentsClientHttpRequestFactory httpFactory = (HttpComponentsClientHttpRequestFactory) factory;
        HttpClient httpClient = httpFactory.getHttpClient();
        assertNotNull(httpClient);
    }
}
