package com.aspera.web.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

class RestTemplateConfigTest {

    @Test
    void nodeClientDoesNotFollowRedirects() throws Exception {
        AtomicInteger targetRequests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/target");
            exchange.sendResponseHeaders(307, -1);
            exchange.close();
        });
        server.createContext("/target", exchange -> {
            targetRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        try {
            var restTemplate = new RestTemplateConfig().restTemplate(new RestTemplateBuilder());
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect";

            ResponseEntity<String> response = restTemplate.postForEntity(url, new HttpEntity<>("{}"), String.class);

            assertThat(response.getStatusCode().value()).isEqualTo(307);
            assertThat(targetRequests.get()).isZero();
        } finally {
            server.stop(0);
        }
    }

    @Test
    void declaredOversizedResponseIsRejectedBeforeItsBodyIsOpened() throws Exception {
        RestTemplateConfig.ResponseSizeLimitInterceptor interceptor =
                new RestTemplateConfig.ResponseSizeLimitInterceptor(4);
        HttpRequest request = mock(HttpRequest.class);
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentLength(5);
        when(response.getHeaders()).thenReturn(headers);
        when(execution.execute(any(), any())).thenReturn(response);

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isInstanceOf(RestTemplateConfig.ResponseSizeLimitException.class)
                .hasMessageContaining("4 byte limit");
        verify(response).close();
        verify(response, never()).getBody();
    }

    @Test
    void unknownLengthResponseIsRejectedWhileReadingPastLimit() throws Exception {
        RestTemplateConfig.ResponseSizeLimitInterceptor interceptor =
                new RestTemplateConfig.ResponseSizeLimitInterceptor(4);
        ClientHttpResponse response = responseWithBody("12345");
        ClientHttpRequestExecution execution = executionReturning(response);

        ClientHttpResponse limited = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThatThrownBy(() -> limited.getBody().readAllBytes())
                .isInstanceOf(RestTemplateConfig.ResponseSizeLimitException.class)
                .hasMessageContaining("4 byte limit");
    }

    @Test
    void responseAtExactLimitCanBeReadToEnd() throws Exception {
        RestTemplateConfig.ResponseSizeLimitInterceptor interceptor =
                new RestTemplateConfig.ResponseSizeLimitInterceptor(4);
        ClientHttpResponse response = responseWithBody("1234");
        ClientHttpRequestExecution execution = executionReturning(response);

        ClientHttpResponse limited = interceptor.intercept(mock(HttpRequest.class), new byte[0], execution);

        assertThat(new String(limited.getBody().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("1234");
    }

    private static ClientHttpResponse responseWithBody(String body) throws Exception {
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getHeaders()).thenReturn(new HttpHeaders());
        when(response.getBody()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        return response;
    }

    private static ClientHttpRequestExecution executionReturning(ClientHttpResponse response) throws Exception {
        ClientHttpRequestExecution execution = mock(ClientHttpRequestExecution.class);
        when(execution.execute(any(), any())).thenReturn(response);
        return execution;
    }
}
