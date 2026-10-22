package com.openitsm;

import com.openitsm.service.SessionService;
import com.openitsm.service.SessionResolution;
import com.openitsm.entity.AuthSessionEntity;
import com.openitsm.common.utils.TokenCodec;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.config.BrowserSecurityFilter;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SecurityBoundaryTest {
    @Test void chunkedBodyIsBoundedBeforeJsonParsing() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-10-14T02:00:00Z"), ZoneOffset.UTC);
        SessionService sessions = mock(SessionService.class);
        String raw = TokenCodec.token();
        String csrf = TokenCodec.csrf(raw);
        AuthSessionEntity session = new AuthSessionEntity(TokenCodec.hash(raw), null, null, clock.instant(), clock.instant(),
            null, clock.instant().plusSeconds(7200), null, TokenCodec.hash(csrf));
        when(sessions.resolve(raw)).thenReturn(new SessionResolution(new AuthContext(session, null, raw), false));
        var filter = new BrowserSecurityFilter(sessions, new ObjectMapper(), "test", "http://localhost:5173", false);
        var req = new MockHttpServletRequest("POST", "/api/v1/auth/login") {
            @Override public long getContentLengthLong() { return -1; }
            @Override public int getContentLength() { return -1; }
        };
        req.setContent(new byte[256 * 1024 + 1]);
        req.setRemoteAddr("127.0.0.1"); req.setCookies(new Cookie("test", raw));
        req.addHeader("Origin", "http://localhost:5173"); req.addHeader("X-CSRF-Token", csrf);
        var res = new MockHttpServletResponse(); var parsed = new AtomicBoolean();
        filter.doFilter(req, res, (request, response) -> parsed.set(true));
        assertThat(res.getStatus()).isEqualTo(413);
        assertThat(parsed.get()).isFalse();
    }


}
