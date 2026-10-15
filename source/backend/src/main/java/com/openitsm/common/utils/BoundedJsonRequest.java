package com.openitsm.common.utils;

import com.openitsm.common.exception.BusinessException;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Read at most limit+1 bytes, including chunked requests, before Jackson gets the stream. */
public final class BoundedJsonRequest extends HttpServletRequestWrapper {
    private final byte[] body;
    private BoundedJsonRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }

    public static HttpServletRequest read(HttpServletRequest request) throws IOException {
        byte[] body = request.getInputStream().readNBytes(256 * 1024 + 1);
        if (body.length > 256 * 1024) throw new BusinessException(413, "REQUEST_TOO_LARGE", "请求正文超过 256 KiB");
        return new BoundedJsonRequest(request, body);
    }

    @Override public int getContentLength() { return body.length; }
    @Override public long getContentLengthLong() { return body.length; }
    @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8)); }
    @Override public ServletInputStream getInputStream() {
        var input = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous JSON API"); }
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] bytes, int off, int len) { return input.read(bytes, off, len); }
        };
    }
}
