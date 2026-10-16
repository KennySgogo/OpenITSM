package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.service.BootstrapService;
import org.springframework.jdbc.core.JdbcTemplate;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real TCP HTTP requests against local profile and a disposable Testcontainers database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class M01FrontendHttpTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) {
        M01IntegrationTest.properties(props);
    }

    @LocalServerPort int port;
    @Autowired BootstrapService bootstrap;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    String csrf;
    HttpClient client = HttpClient.newBuilder()
        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
        .connectTimeout(Duration.ofSeconds(5)).build();

    HttpResponse<String> call(String method, String path, String body, String key, Long version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1" + path))
            .timeout(Duration.ofSeconds(10));
        if (!method.equals("GET")) request.header("Origin", "http://localhost:5173").header("X-CSRF-Token", csrf);
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).get("data"); }

    void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        var body = json.readTree(response.body());
        assertThat(body.path("error").path("code").asText()).isEqualTo(code);
        assertThat(body.path("error").path("message").asText()).isNotBlank();
        assertThat(body.path("error").path("fieldErrors").isArray()).isTrue();
        assertThat(body.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(body.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    @Test
    void frontendContractWorksOverRealHttp() throws Exception {
        for (String health : new String[]{"live", "ready"}) {
            var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health/" + health))
                .timeout(Duration.ofSeconds(10)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
        }
        // The database belongs exclusively to this test container, never the normal local database.
        bootstrap.initialize("Frontend HTTP Admin", "frontend-http@example.com", "TestPassword1234");
        csrf = data(call("GET", "/auth/csrf", null, null, null)).get("csrfToken").asText();
        var invalid = call("POST", "/auth/login", "{\"email\":\"frontend-http@example.com\",\"password\":\"wrong\"}", null, null);
        assertThat(invalid.statusCode()).isEqualTo(401);
        assertThat(invalid.body()).contains("AUTH_INVALID");
        var login = call("POST", "/auth/login", "{\"email\":\"frontend-http@example.com\",\"password\":\"TestPassword1234\",\"returnTo\":\"/admin/users\"}", null, null);
        assertThat(login.statusCode()).isEqualTo(200);
        assertThat(data(login).get("redirectPath").asText()).isEqualTo("/admin/users");
        csrf = data(login).get("csrfToken").asText();
        assertThat(call("GET", "/auth/me", null, null, null).statusCode()).isEqualTo(200);
        var page = call("GET", "/users", null, null, null);
        assertThat(data(page).get("items").get(0).has("lockedUntil")).isTrue();
        assertThat(data(page).get("pageSize").asInt()).isEqualTo(20);
        String invitation = "{\"name\":\"HTTP Member\",\"email\":\"frontend-member@example.com\",\"role\":\"EMPLOYEE\"}";
        String key = UUID.randomUUID().toString();
        var invite = call("POST", "/users", invitation, key, null);
        assertThat(invite.statusCode()).isEqualTo(201);
        assertThat(data(call("POST", "/users", invitation, key, null)).get("replayed").asBoolean()).isTrue();
        assertError(call("POST", "/users", invitation.replace("HTTP Member", "Other Member"), key, null), 409, "IDEMPOTENCY_CONFLICT");
        String id = data(invite).get("resourceId").asText();
        long version = data(invite).get("acceptedVersion").asLong();
        String updateBody = "{\"name\":\"Updated HTTP Member\"}";
        String updateKey = UUID.randomUUID().toString();
        assertError(call("PATCH", "/users/" + id, updateBody, updateKey, null), 428, "PRECONDITION_REQUIRED");
        var update = call("PATCH", "/users/" + id, updateBody, updateKey, version);
        assertThat(update.statusCode()).isEqualTo(200);
        var replay = call("PATCH", "/users/" + id, updateBody, updateKey, version);
        assertThat(replay.statusCode()).isEqualTo(200);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(data(replay).get("replayed").asBoolean()).isTrue();
        assertError(call("PATCH", "/users/" + id, updateBody, updateKey, null), 428, "PRECONDITION_REQUIRED");
        assertError(call("PATCH", "/users/" + id, "{\"name\":\"Different Member\"}", updateKey, version), 409, "IDEMPOTENCY_CONFLICT");
        assertThat(db.queryForObject("SELECT name FROM app_user WHERE id=?", String.class, UUID.fromString(id))).isEqualTo("Updated HTTP Member");
        assertThat(db.queryForObject("SELECT version FROM app_user WHERE id=?", Long.class, UUID.fromString(id))).isEqualTo(version + 1);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE object_id=?", Integer.class, UUID.fromString(id))).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE recipient_id=?", Integer.class, UUID.fromString(id))).isEqualTo(1);
        var conflict = call("PATCH", "/users/" + id, "{\"name\":\"Stale HTTP Member\"}", UUID.randomUUID().toString(), version);
        assertThat(conflict.statusCode()).isEqualTo(409);
        assertThat(conflict.body()).contains("VERSION_CONFLICT");
        var known = call("POST", "/auth/password-reset-requests", "{\"email\":\"frontend-http@example.com\"}", null, null);
        var unknown = call("POST", "/auth/password-reset-requests", "{\"email\":\"nonexistent-http@example.com\"}", null, null);
        assertThat(known.statusCode()).isEqualTo(202);
        assertThat(unknown.statusCode()).isEqualTo(202);
        assertThat(data(known).get("message").asText()).isEqualTo(data(unknown).get("message").asText());
        assertThat(call("POST", "/auth/logout", null, null, null).statusCode()).isEqualTo(204);
        var expired = call("GET", "/auth/me", null, null, null);
        assertThat(expired.statusCode()).isEqualTo(401);
        assertThat(expired.body()).contains("SESSION_EXPIRED");
        System.out.println("PASS real HTTP: live/ready UP, invalid login, login redirectPath, me, member list/lockedUntil, invitation/body conflict, If-Match 428, matching version, idempotent replay/body conflict without duplicate writes, stale version 409, uniform password reset message, logout and expired session 401");
        // Opt-in local browser QA; never enabled in normal verify runs. Contains only test accounts.
        String hold = System.getenv("OPENITSM_FRONTEND_BROWSER_HOLD_SECONDS");
        if (hold != null) {
            int seconds = Math.min(300, Math.max(0, Integer.parseInt(hold)));
            System.out.println("FRONTEND_BROWSER_FIXTURE_PORT=" + port);
            Thread.sleep(seconds * 1000L);
        }
    }
}
