package com.aspera.web.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class JsonRequestSizeLimitFilterTest {

    private final JsonRequestSizeLimitFilter filter = new JsonRequestSizeLimitFilter();

    @Test
    void rejectsDeclaredJsonBodyAboveLimitWithoutInvokingChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files/dir-sizes");
        request.setContentType("application/json");
        request.setContent(new byte[JsonRequestSizeLimitFilter.MAX_JSON_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(invoked.get()).isFalse();
    }

    @Test
    void rejectsChunkedJsonBodyThatActuallyExceedsLimit() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files/dir-sizes") {
            @Override
            public int getContentLength() {
                return -1;
            }

            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/problem+json");
        request.setContent(new byte[JsonRequestSizeLimitFilter.MAX_JSON_REQUEST_BYTES + 1]);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<Boolean> invoked = new AtomicReference<>(false);

        filter.doFilter(request, response, (req, res) -> invoked.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(invoked.get()).isFalse();
    }

    @Test
    void passesBoundedJsonWithReplayableBody() throws Exception {
        byte[] body = "{\"paths\":[\"/safe\"]}".getBytes(StandardCharsets.UTF_8);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/files/dir-sizes");
        request.setContentType("application/json; charset=UTF-8");
        request.setContent(body);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<byte[]> observed = new AtomicReference<>();
        FilterChain chain = (req, res) -> observed.set(req.getInputStream().readAllBytes());

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(observed.get()).isEqualTo(body);
    }
}
