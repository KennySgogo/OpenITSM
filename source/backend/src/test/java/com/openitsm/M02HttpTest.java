package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.BootstrapService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

/** Real HTTP and PostgreSQL; all fixtures belong to the isolated M01/M02 test database. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class M02HttpTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) { M01IntegrationTest.properties(props); }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BootstrapService bootstrap;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    UUID admin;
    final String password = "TestPassword1234";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach
    void setUp() {
        db.execute("TRUNCATE idempotency_record,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        admin = bootstrap.initialize("M02 Admin", "m02-admin@example.com", password);
    }

    HttpClient client() {
        return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
    }

    HttpResponse<String> call(Browser browser, String method, String path, String body, String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (!method.equals("GET")) {
            request.header("Origin", "http://localhost:5173");
            if (browser.csrf() != null) request.header("X-CSRF-Token", browser.csrf());
        }
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return browser.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }

    Browser signed(String email) throws Exception {
        Browser anon = new Browser(client(), null);
        String csrf = data(call(anon, "GET", "/api/v1/auth/csrf", null, null)).path("csrfToken").asText();
        var login = call(new Browser(anon.client(), csrf), "POST", "/api/v1/auth/login",
            json.writeValueAsString(Map.of("email", email, "password", password)), null);
        assertThat(login.statusCode()).isEqualTo(200);
        return new Browser(anon.client(), data(login).path("csrfToken").asText());
    }

    Map<String, String> fields() {
        return new LinkedHashMap<>(Map.of("title", "无法连接网络", "description", "办公电脑无法连接内网",
            "businessTypeId", TicketTypeTestSupport.type(db, "网络").toString(), "impactScope", "本人"));
    }

    HttpResponse<String> create(Browser browser, Map<String, String> body, String key) throws Exception {
        return call(browser, "POST", "/api/v1/tickets", json.writeValueAsString(body), key);
    }

    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var root = json.readTree(response.body());
        assertThat(root.path("error").path("code").asText()).isEqualTo(code);
        assertThat(root.path("error").path("message").asText()).isNotBlank();
        assertThat(root.path("error").path("fieldErrors").isArray()).isTrue();
        assertThat(root.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(root.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    @Test
    void allRolesCreatePersistedTicketsWithUniqueNumbers() throws Exception {
        List<String> numbers = new ArrayList<>();
        String sharedKey = UUID.randomUUID().toString();
        for (String role : List.of("ADMIN", "ENGINEER", "EMPLOYEE")) {
            UUID actorId = admin;
            String email = "m02-admin@example.com";
            if (!role.equals("ADMIN")) {
                var user = new UserEntity(); user.id = UUID.randomUUID(); user.name = role;
                user.emailNormalized = role.toLowerCase() + "@example.com"; user.role = role;
                user.status = "ACTIVE"; user.passwordHash = passwords.encode(password); user.updatedAt = Instant.now();
                users.insert(user); actorId = user.id; email = user.emailNormalized;
            }
            var body = fields(); body.put("title", "  " + "标题".repeat(150) + "  ");
            var response = create(signed(email), body, sharedKey);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            JsonNode result = data(response);
            UUID id = UUID.fromString(result.path("id").asText());
            assertThat(result.path("number").asText()).matches("ITSM-[1-9][0-9]*");
            assertThat(result.path("replayed").asBoolean()).isFalse();
            numbers.add(result.path("number").asText());
            var stored = db.queryForMap("SELECT * FROM ticket WHERE id=?", id);
            assertThat(stored.get("number")).isEqualTo(result.path("number").asText());
            assertThat(stored.get("title")).isEqualTo(body.get("title").strip());
            assertThat(stored.get("description")).isEqualTo(body.get("description"));
            assertThat(stored.get("business_type_id").toString()).isEqualTo(body.get("businessTypeId"));
            assertThat(stored.get("impact_scope")).isEqualTo(body.get("impactScope"));
            assertThat(stored.get("requester_id")).isEqualTo(actorId);
            assertThat(stored.get("assignee_id")).isNull();
            assertThat(stored.get("status")).isEqualTo("NEW");
            assertThat(stored.get("created_at")).isNotNull();
        }
        assertThat(numbers).doesNotHaveDuplicates();
        assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isEqualTo(3);
    }

    @Test
    void replayAndChangedOriginalFieldsNeverCreateAnotherTicket() throws Exception {
        Browser browser = signed("m02-admin@example.com");
        String key = UUID.randomUUID().toString();
        var first = create(browser, fields(), key);
        assertThat(first.statusCode()).isEqualTo(201);
        // JSON property order is not part of the existing DTO-based digest.
        var replay = call(browser, "POST", "/api/v1/tickets",
            json.writeValueAsString(new java.util.TreeMap<>(fields())), key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(data(replay).path("id")).isEqualTo(data(first).path("id"));
        assertThat(data(replay).path("number")).isEqualTo(data(first).path("number"));
        assertThat(data(replay).path("replayed").asBoolean()).isTrue();
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        for (String field : fields().keySet()) {
            var changed = fields(); changed.put(field, field.equals("businessTypeId") ? UUID.randomUUID().toString() : changed.get(field) + " ");
            error(create(browser, changed, key), 409, "IDEMPOTENCY_CONFLICT");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM idempotency_record WHERE operation='POST /api/v1/tickets'", Integer.class)).isEqualTo(1);
    }

    @Test
    void invalidFieldsKeysAndUnknownFieldsDoNotPersistTickets() throws Exception {
        Browser browser = signed("m02-admin@example.com");
        for (String field : fields().keySet()) {
            var missing = fields(); missing.remove(field);
            var result = create(browser, missing, UUID.randomUUID().toString());
            error(result, 400, "VALIDATION_FAILED");
            assertThat(json.readTree(result.body()).path("error").path("fieldErrors").get(0).path("field").asText()).isEqualTo(field);
            var blank = fields(); blank.put(field, " \t\n ");
            error(create(browser, blank, UUID.randomUUID().toString()), 400, "VALIDATION_FAILED");
            var nil = fields(); nil.put(field, null);
            error(create(browser, nil, UUID.randomUUID().toString()), 400, "VALIDATION_FAILED");
            for (Object value : List.of(123, true, List.of("text"), Map.of("text", "value"))) {
                var wrongType = new LinkedHashMap<String, Object>(fields()); wrongType.put(field, value);
                error(call(browser, "POST", "/api/v1/tickets", json.writeValueAsString(wrongType), UUID.randomUUID().toString()),
                    400, "VALIDATION_FAILED");
            }
        }
        for (String field : List.of("requesterId", "status", "assigneeId")) {
            var extra = fields(); extra.put(field, "injected");
            error(create(browser, extra, UUID.randomUUID().toString()), 400, "VALIDATION_FAILED");
        }
        error(create(browser, fields(), null), 400, "VALIDATION_FAILED");
        error(create(browser, fields(), "not-a-uuid"), 400, "VALIDATION_FAILED");
        assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class)).isZero();
    }

    @Test
    void authenticationCsrfPayloadLimitAndHealthWorkOnRunningApplication() throws Exception {
        error(create(new Browser(client(), null), fields(), UUID.randomUUID().toString()), 401, "SESSION_EXPIRED");
        Browser browser = signed("m02-admin@example.com");
        error(create(new Browser(browser.client(), null), fields(), UUID.randomUUID().toString()), 403, "CSRF_INVALID");
        var large = fields(); large.put("description", "x".repeat(256 * 1024));
        error(create(browser, large, UUID.randomUUID().toString()), 413, "REQUEST_TOO_LARGE");
        for (String health : List.of("live", "ready")) {
            var response = call(browser, "GET", "/health/" + health, null, null);
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(json.readTree(response.body()).path("status").asText()).isEqualTo("UP");
        }
        assertThat(call(browser, "POST", "/api/v1/auth/logout", null, null).statusCode()).isEqualTo(204);
        error(create(browser, fields(), UUID.randomUUID().toString()), 401, "SESSION_EXPIRED");
        assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isZero();
    }

    @Test
    void concurrentRetriesCreateOnceAndDistinctKeysReceiveUniqueNumbers() throws Exception {
        Browser browser = signed("m02-admin@example.com");
        String key = UUID.randomUUID().toString();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return create(browser, fields(), key); });
            var second = pool.submit(() -> { start.await(); return create(browser, fields(), key); });
            start.countDown();
            for (var response : List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS))) {
                if (response.statusCode() == 409) error(response, 409, "REQUEST_IN_PROGRESS");
                else assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
            }
            assertThat(create(browser, fields(), key).statusCode()).isEqualTo(201);
            assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class)).isEqualTo(1);
            var third = pool.submit(() -> create(browser, fields(), UUID.randomUUID().toString()));
            var fourth = pool.submit(() -> create(browser, fields(), UUID.randomUUID().toString()));
            var a = third.get(20, TimeUnit.SECONDS); var b = fourth.get(20, TimeUnit.SECONDS);
            assertThat(a.statusCode()).isEqualTo(201); assertThat(b.statusCode()).isEqualTo(201);
            assertThat(data(a).path("number")).isNotEqualTo(data(b).path("number"));
            assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isEqualTo(3);
        }
    }
}
