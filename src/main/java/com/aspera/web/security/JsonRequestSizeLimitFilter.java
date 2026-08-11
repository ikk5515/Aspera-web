package com.aspera.web.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rejects oversized JSON before MVC/Jackson can allocate an unbounded request body.
 * Form posts are bounded separately by the embedded server configuration.
 */
public final class JsonRequestSizeLimitFilter extends OncePerRequestFilter {

    public static final int MAX_JSON_REQUEST_BYTES = 1024 * 1024;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String contentType = request.getContentType();
        if (contentType == null) {
            return true;
        }
        String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return !("application/json".equals(mediaType) || mediaType.endsWith("+json"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        long declaredLength = request.getContentLengthLong();
        if (declaredLength > MAX_JSON_REQUEST_BYTES) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }

        byte[] body = request.getInputStream().readNBytes(MAX_JSON_REQUEST_BYTES + 1);
        if (body.length > MAX_JSON_REQUEST_BYTES) {
            response.sendError(HttpStatus.CONTENT_TOO_LARGE.value());
            return;
        }

        filterChain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        private CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            return new CachedBodyServletInputStream(body);
        }

        @Override
        public BufferedReader getReader() {
            Charset charset = StandardCharsets.UTF_8;
            String encoding = getCharacterEncoding();
            if (encoding != null) {
                try {
                    charset = Charset.forName(encoding);
                } catch (IllegalArgumentException ignored) {
                    // Invalid request encodings fall back to UTF-8 and are rejected by parsing if needed.
                }
            }
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }

    private static final class CachedBodyServletInputStream extends ServletInputStream {
        private final ByteArrayInputStream delegate;

        private CachedBodyServletInputStream(byte[] body) {
            this.delegate = new ByteArrayInputStream(body);
        }

        @Override
        public int read() {
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public boolean isFinished() {
            return delegate.available() == 0;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(ReadListener listener) {
            if (listener == null) {
                throw new IllegalArgumentException("ReadListener is required.");
            }
            try {
                if (isFinished()) {
                    listener.onAllDataRead();
                } else {
                    listener.onDataAvailable();
                }
            } catch (IOException ex) {
                listener.onError(ex);
            }
        }
    }
}
