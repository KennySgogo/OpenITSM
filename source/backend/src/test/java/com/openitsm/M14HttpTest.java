package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.*;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.*;
import static org.assertj.core.api.Assertions.*;

/** Real browser HTTP, PostgreSQL and private HTTP transport; no external providers or real email. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(M01IntegrationTest.TimeConfig.class)
class M14HttpTest {
    static final String TOKEN = "m14-private-http-test-token-1234567890";
    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile String response = "{\"status\":\"SUCCESS\"}";
    static volatile int responseCode = 200;
    static volatile CountDownLatch entered, release;
    static final HttpServer PYTHON;
    static {
        try {
            PYTHON = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PYTHON.createContext("/internal/v1/model-connection-tests", exchange -> {
                CALLS.incrementAndGet();
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization")) || request.contains("smtp") || request.contains("ticket")) {
                    exchange.sendResponseHeaders(401, -1); exchange.close(); return;
                }
                if (entered != null) entered.countDown();
                try { if (release != null) release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseCode, body.length); exchange.getResponseBody().write(body); exchange.close();
            });
            PYTHON.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> PYTHON.stop(0)));
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        M01IntegrationTest.properties(props);
        props.add("openitsm.ai.internal-token", () -> TOKEN);
        props.add("openitsm.ai.service-url", () -> "http://127.0.0.1:" + PYTHON.getAddress().getPort());
        props.add("spring.mail.host", () -> "127.0.0.1");
        props.add("spring.mail.port", () -> 2525);
        props.add("spring.mail.username", () -> "bootstrap-user");
        props.add("spring.mail.password", () -> "bootstrap-smtp-test-secret");
        props.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
        props.add("openitsm.account-mail.from", () -> "sender@example.com");
        props.add("openitsm.model.base-url", () -> "https://bootstrap.example/v1");
        props.add("openitsm.model.name", () -> "bootstrap-model");
        props.add("openitsm.model.api-key", () -> "bootstrap-model-test-secret");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired UserMapper users;
    @Autowired BootstrapService bootstrap;
    @Autowired PasswordEncoder passwords;
    @Autowired RuntimeSettingsService runtime;
    @Autowired M01IntegrationTest.TestClock clock;
    Browser admin, employee, engineer;
    UUID adminId;
    final String password = "TestPassword1234";
    String origin = "http://localhost:5173";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach void setUp() throws Exception {
        clock.now = Instant.parse("2026-10-20T00:00:00Z");
        db.execute("TRUNCATE team_settings,ai_connection_status,idempotency_record,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        adminId = bootstrap.initialize("M14 Admin", "m14-admin@example.com", password);
        for (String role : List.of("EMPLOYEE", "ENGINEER")) {
            var user = new UserEntity(); user.id = UUID.randomUUID(); user.emailNormalized = role.toLowerCase() + "@example.com";
            user.name = role; user.role = role; user.status = "ACTIVE"; user.passwordHash = passwords.encode(password); user.updatedAt = clock.instant(); users.insert(user);
        }
        admin = signed("m14-admin@example.com"); employee = signed("employee@example.com"); engineer = signed("engineer@example.com");
        CALLS.set(0); response = "{\"status\":\"SUCCESS\"}"; responseCode = 200; entered = null; release = null;
    }
    HttpResponse<String> call(Browser browser, String method, String path, Object body, String key, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
        if (!method.equals("GET")) {
            request.header("Origin", origin);
            if (browser.csrf != null) request.header("X-CSRF-Token", browser.csrf);
        }
        if (key != null) request.header("Idempotency-Key", key);
        if (version != null) request.header("If-Match", version);
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return browser.client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    Browser signed(String email) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var anon = new Browser(client, null);
        String csrf = data(call(anon, "GET", "/api/v1/auth/csrf", null, null, null)).path("csrfToken").asText();
        var login = call(new Browser(client, csrf), "POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null, null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Browser(client, data(login).path("csrfToken").asText());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    HttpResponse<String> patch(Object body, int version) throws Exception { return call(admin, "PATCH", "/api/v1/settings", body, UUID.randomUUID().toString(), "\"" + version + "\""); }
    HttpResponse<String> test() throws Exception { return call(admin, "POST", "/api/v1/settings/model-connection-tests", null, null, null); }
    void error(HttpResponse<String> value, int status, String code) throws Exception {
        assertThat(value.statusCode()).as(value.body()).isEqualTo(status);
        assertThat(json.readTree(value.body()).path("error").path("code").asText()).isEqualTo(code);
        assertThat(value.body()).doesNotContain("bootstrap-model-test-secret", "bootstrap-smtp-test-secret", TOKEN);
    }

    @Test void bootstrapReadIsRedactedAndHasNoSideEffects() throws Exception {
        var response = call(admin, "GET", "/api/v1/settings", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("ETag").orElseThrow()).isEqualTo("\"0\"");
        assertThat(data(response).path("smtp").path("passwordConfigured").asBoolean()).isTrue();
        assertThat(data(response).path("lastTest").path("status").asText()).isEqualTo("NOT_TESTED");
        assertThat(data(response).path("lastCall").path("status").asText()).isEqualTo("NO_CALLS");
        assertThat(response.body()).doesNotContain("bootstrap-model-test-secret", "bootstrap-smtp-test-secret", "apiKey\"", "password\"");
        assertThat(db.queryForObject("SELECT count(*) FROM team_settings", Integer.class)).isZero();
        assertThat(CALLS).hasValue(0);
    }
    @Test void onlyAdminsCanReadWriteOrTestAndCsrfIsRequired() throws Exception {
        for (Browser browser : List.of(employee, engineer)) {
            error(call(browser, "GET", "/api/v1/settings", null, null, null), 403, "FORBIDDEN");
            error(call(browser, "PATCH", "/api/v1/settings", Map.of("teamName", "Denied"), UUID.randomUUID().toString(), "\"0\""), 403, "FORBIDDEN");
            error(call(browser, "POST", "/api/v1/settings/model-connection-tests", null, null, null), 403, "FORBIDDEN");
        }
        error(call(new Browser(admin.client, null), "PATCH", "/api/v1/settings", Map.of("teamName", "Denied"), UUID.randomUUID().toString(), "\"0\""), 403, "CSRF_INVALID");
        assertThat(CALLS).hasValue(0);
    }
    @Test void partialSavePreservesBootstrapEncryptsSecretsAndReplaysWithoutConnecting() throws Exception {
        String key = UUID.randomUUID().toString(); var body = Map.of("teamName", "Service Team");
        var first = call(admin, "PATCH", "/api/v1/settings", body, key, "\"0\"");
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);
        assertThat(data(first).path("acceptedVersion").asInt()).isEqualTo(1);
        var replay = call(admin, "PATCH", "/api/v1/settings", body, key, "\"0\"");
        assertThat(data(replay).path("replayed").asBoolean()).isTrue();
        error(call(admin, "PATCH", "/api/v1/settings", Map.of("teamName", "Different"), key, "\"0\""), 409, "IDEMPOTENCY_CONFLICT");
        error(patch(body, 0), 409, "VERSION_CONFLICT");
        var row = db.queryForMap("SELECT * FROM team_settings");
        assertThat(row.get("smtp_password").toString()).doesNotContain("bootstrap-smtp-test-secret");
        assertThat(row.get("model_api_key").toString()).doesNotContain("bootstrap-model-test-secret");
        assertThat(runtime.current().smtp().password()).isEqualTo("bootstrap-smtp-test-secret");
        assertThat(runtime.current().model().model()).isEqualTo("bootstrap-model");
        assertThat(runtime.current().aiEnabled()).isFalse(); assertThat(CALLS).hasValue(0);
    }
    @Test void explicitClearNeverFallsBackAndCannotLeaveAiEnabledWithoutKey() throws Exception {
        assertThat(patch(Map.of("aiEnabled", true), 0).statusCode()).isEqualTo(200);
        assertThat(runtime.requireAiConnection().version()).isEqualTo(1);
        assertThatCode(() -> runtime.recheckAiConnection(1)).doesNotThrowAnyException();
        assertThat(patch(Map.of("teamName", "Changed while enabled"), 1).statusCode()).isEqualTo(200);
        assertThatThrownBy(() -> runtime.recheckAiConnection(1)).hasMessageContaining("AI 配置已变化");
        error(patch(Map.of("model", Map.of("clearApiKey", true)), 2), 409, "INVALID_STATE");
        assertThat(patch(Map.of("aiEnabled", false, "model", Map.of("clearApiKey", true), "smtp", Map.of("clearPassword", true)), 2).statusCode()).isEqualTo(200);
        assertThat(runtime.current().model().apiKey()).isNull(); assertThat(runtime.current().smtp().password()).isNull();
        error(patch(Map.of("aiEnabled", true), 3), 409, "INVALID_STATE");
        error(test(), 503, "AI_NOT_CONFIGURED");
        assertThatThrownBy(runtime::requireAiConnection).hasMessageContaining("AI 已关闭");
        assertThat(CALLS).hasValue(0);
    }
    @Test void rejectsMalformedPartialDocumentsAndDoesNotChangeStoredVersion() throws Exception {
        for (String body : List.of("{}", "{\"teamName\":null}", "{\"teamName\":\" \"}", "{\"smtp\":null}",
            "{\"model\":{\"apiKey\":null}}", "{\"model\":{\"apiKey\":\"\"}}", "{\"model\":{\"apiKey\":\"*****\"}}",
            "{\"model\":{\"clearApiKey\":true,\"apiKey\":\"new\"}}", "{\"smtp\":{\"port\":65536}}",
            "{\"smtp\":{\"port\":1.2}}", "{\"smtp\":{\"security\":\"BAD\"}}", "{\"script\":\"run\"}",
            "{\"model\":{\"baseUrl\":\"http://remote.example/v1\"}}", "{\"model\":{\"baseUrl\":\"https://name:secret@example.com\"}}")) {
            error(patch(json.readTree(body), 0), 400, "VALIDATION_FAILED");
        }
        assertThat(db.queryForObject("SELECT count(*) FROM team_settings", Integer.class)).isZero();
    }
    @Test void disabledAiCanTestSavedConnectionAndLimitsPersistEvenOnFailure() throws Exception {
        error(test(), 503, "AI_NOT_CONFIGURED");
        assertThat(patch(Map.of("teamName", "Saved"), 0).statusCode()).isEqualTo(200);
        error(call(admin, "POST", "/api/v1/settings/model-connection-tests", Map.of("prompt", "custom"), null, null), 400, "VALIDATION_FAILED");
        assertThat(test().statusCode()).isEqualTo(200); assertThat(CALLS).hasValue(1);
        assertThat(runtime.current().aiEnabled()).isFalse();
        var limited = test(); error(limited, 429, "RATE_LIMITED"); assertThat(limited.headers().firstValue("Retry-After")).contains("1");
        clock.now = clock.now.plusMillis(999);
        error(test(), 429, "RATE_LIMITED");
        clock.now = clock.now.plusMillis(1);
        response = "{\"code\":\"DEPENDENCY_UNAVAILABLE\"}"; responseCode = 503;
        error(test(), 503, "DEPENDENCY_UNAVAILABLE"); error(test(), 429, "RATE_LIMITED");
        assertThat(data(call(admin, "GET", "/api/v1/settings", null, null, null)).path("lastTest").path("status").asText()).isEqualTo("DEPENDENCY_UNAVAILABLE");
        assertThat(runtime.current().version()).isEqualTo(1);
    }
    @Test void invalidPythonResponsesAndTimeoutsUsePublicErrors() throws Exception {
        assertThat(patch(Map.of("teamName", "Saved"), 0).statusCode()).isEqualTo(200);
        response = "{\"code\":\"AI_INVALID_RESPONSE\"}"; responseCode = 503;
        error(test(), 503, "AI_INVALID_RESPONSE");
        clock.advance(1); response = "{\"code\":\"AI_TIMEOUT\"}"; responseCode = 504;
        error(test(), 504, "AI_TIMEOUT");
        clock.advance(1); response = "not-json"; responseCode = 200;
        error(test(), 503, "AI_INVALID_RESPONSE");
        clock.advance(1); response = "x".repeat(17000);
        error(test(), 503, "AI_INVALID_RESPONSE");
    }
    @Test void concurrentSavesHaveOneWinnerAndNoDuplicateRows() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return patch(Map.of("teamName", "One"), 0); });
            var second = pool.submit(() -> { start.await(); return patch(Map.of("teamName", "Two"), 0); });
            start.countDown();
            assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(db.queryForObject("SELECT count(*) FROM team_settings", Integer.class)).isEqualTo(1);
        assertThat(runtime.current().version()).isEqualTo(1);
    }
    @Test void smtpDeliveryUsesSavedConnectionAndFromOnEachDeliveryWithoutChangingSwitches() throws Exception {
        var env = new org.springframework.mock.env.MockEnvironment(); env.setActiveProfiles("test");
        var deployment = new org.springframework.boot.autoconfigure.mail.MailProperties();
        deployment.setHost("old.invalid"); deployment.setPort(1);
        var config = new com.openitsm.config.AccountMailConfig(true, "old@example.com", "http://localhost:5173", deployment, env);
        var service = new com.openitsm.service.impl.SmtpMailServiceImpl(config.javaMailSender(), config, runtime);
        for (int version = 0; version < 2; version++) {
            try (var capture = new AccountMailSmtpTest.SmtpCapture()) {
                String from = "saved" + version + "@example.com";
                assertThat(patch(Map.of("smtp", Map.of("host", "127.0.0.1", "port", capture.port(), "username", "", "from", from, "security", "NONE", "clearPassword", true)), version).statusCode()).isEqualTo(200);
                service.sendTicket("receiver@example.com", UUID.randomUUID(), "ITSM-1", "assignments");
                assertThat(capture.message().getFrom()[0].toString()).isEqualTo(from);
            }
        }
        assertThat(CALLS).hasValue(0);
        assertThat(runtime.current().smtp().password()).isNull();
    }

    @Test void v13UpgradePreservesExistingDataAndCreatesNoFakeConfigurationOrStatuses() throws Exception {
        String schema = "m14_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        String jdbcUrl, username, dbPassword;
        try (var connection = db.getDataSource().getConnection()) {
            jdbcUrl = connection.getMetaData().getURL(); username = connection.getMetaData().getUserName();
        }
        dbPassword = System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL") == null ? M01IntegrationTest.PG.getPassword() : System.getenv("OPENITSM_TEST_DB_PASSWORD");
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(jdbcUrl, username, dbPassword).schemas(schema)
            .locations("classpath:db/migration");
        flyway.target("13").load().migrate();
        db.execute("INSERT INTO " + schema + ".ticket_type(id,name,active,version) VALUES ('" + UUID.randomUUID() + "','Keep type',true,0)");
        var latest = flyway.target("14").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket_type", Integer.class)).isEqualTo(1);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".team_settings", Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ai_connection_status", Integer.class)).isZero();
        assertThat(latest.migrate().migrationsExecuted).isZero();
    }
    @Test void changingConfigurationDuringTestKeepsOldResultTiedToOldVersion() throws Exception {
        assertThat(patch(Map.of("teamName", "Saved"), 0).statusCode()).isEqualTo(200);
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var result = pool.submit(this::test);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(patch(Map.of("teamName", "New version"), 1).statusCode()).isEqualTo(200);
            release.countDown();
            assertThat(data(result.get()).path("current").asBoolean()).isFalse();
        } finally { release.countDown(); entered = null; release = null; }
        var last = data(call(admin, "GET", "/api/v1/settings", null, null, null)).path("lastTest");
        assertThat(last.path("testedVersion").asInt()).isEqualTo(1); assertThat(last.path("current").asBoolean()).isFalse();
        assertThatThrownBy(() -> runtime.recheckAiConnection(1)).hasMessageContaining("AI 已关闭");
    }

    @AfterAll void packagedVerification() throws Exception {
        if ("true".equals(System.getenv("OPENITSM_M14_PACKAGED_VERIFY"))) {
            setUp();
            M14PackagedSupport.verify(this);
        }
    }
}
