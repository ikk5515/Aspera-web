package com.aspera.web.config;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.restclient.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestTemplate;

@Configuration
public class RestTemplateConfig {

    static final long MAX_NODE_RESPONSE_BYTES = 10L * 1024 * 1024;

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        // Keep the JVM trust store and hostname verification enabled. Private Node
        // certificates must be imported into the runtime trust store.
        return builder
                .connectTimeout(Duration.ofSeconds(5))
                .readTimeout(Duration.ofSeconds(30))
                .redirects(HttpRedirects.DONT_FOLLOW)
                .additionalInterceptors(new ResponseSizeLimitInterceptor(MAX_NODE_RESPONSE_BYTES))
                .build();
    }

    static final class ResponseSizeLimitInterceptor implements ClientHttpRequestInterceptor {
        private final long maximumBytes;

        ResponseSizeLimitInterceptor(long maximumBytes) {
            if (maximumBytes < 1) {
                throw new IllegalArgumentException("Response size limit must be positive.");
            }
            this.maximumBytes = maximumBytes;
        }

        @Override
        public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                ClientHttpRequestExecution execution) throws IOException {
            ClientHttpResponse response = execution.execute(request, body);
            long declaredLength = response.getHeaders().getContentLength();
            if (declaredLength > maximumBytes) {
                response.close();
                throw new ResponseSizeLimitException(maximumBytes);
            }
            return new SizeLimitedClientHttpResponse(response, maximumBytes);
        }
    }

    static final class ResponseSizeLimitException extends IOException {
        ResponseSizeLimitException(long maximumBytes) {
            super("Node response exceeded the configured " + maximumBytes + " byte limit.");
        }
    }

    private static final class SizeLimitedClientHttpResponse implements ClientHttpResponse {
        private final ClientHttpResponse delegate;
        private final long maximumBytes;
        private InputStream limitedBody;

        private SizeLimitedClientHttpResponse(ClientHttpResponse delegate, long maximumBytes) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText() throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public InputStream getBody() throws IOException {
            if (limitedBody == null) {
                limitedBody = new SizeLimitedInputStream(delegate.getBody(), maximumBytes);
            }
            return limitedBody;
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class SizeLimitedInputStream extends FilterInputStream {
        private final long maximumBytes;
        private long bytesRead;

        private SizeLimitedInputStream(InputStream delegate, long maximumBytes) {
            super(delegate);
            this.maximumBytes = maximumBytes;
        }

        @Override
        public int read() throws IOException {
            if (bytesRead >= maximumBytes) {
                return rejectExtraByteOrEndOfStream();
            }
            int value = super.read();
            if (value >= 0) {
                bytesRead++;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            long remaining = maximumBytes - bytesRead;
            if (remaining <= 0) {
                return rejectExtraByteOrEndOfStream();
            }
            int allowed = (int) Math.min(length, remaining);
            int count = super.read(buffer, offset, allowed);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            long remaining = maximumBytes - bytesRead;
            if (remaining <= 0) {
                return rejectExtraByteOrEndOfStream() < 0 ? 0 : 1;
            }
            long skipped = super.skip(Math.min(count, remaining));
            bytesRead += skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), Math.max(0, maximumBytes - bytesRead));
        }

        @Override
        public boolean markSupported() {
            return false;
        }

        @Override
        public synchronized void mark(int readLimit) {
            // Mark/reset is disabled so callers cannot reset and bypass the cumulative limit.
        }

        @Override
        public synchronized void reset() throws IOException {
            throw new IOException("mark/reset is not supported on a size-limited response.");
        }

        private int rejectExtraByteOrEndOfStream() throws IOException {
            int extraByte = super.read();
            if (extraByte < 0) {
                return -1;
            }
            throw new ResponseSizeLimitException(maximumBytes);
        }
    }
}
