package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.BootstrapService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** TCP HTTP against the actual Spring application and isolated PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(M01IntegrationTest.TimeConfig.class)
class M08HttpTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) { M01IntegrationTest.properties(props); }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired UserMapper users;
    @Autowired BootstrapService bootstrap;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    @Autowired M01IntegrationTest.TestClock clock;
    Browser admin, employee, engineer, stranger;
    UUID employeeId, engineerId;
    final String password = "TestPassword1234";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach
    void setUp() throws Exception {
        clock.now = Instant.parse("2026-10-19T00:00:00Z");
        db.execute("TRUNCATE idempotency_record,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        bootstrap.initialize("M08 Admin", "m08-admin@example.com", password);
        employeeId = active("m08-employee@example.com", "EMPLOYEE");
        engineerId = active("m08-engineer@example.com", "ENGINEER");
        active("m08-stranger@example.com", "EMPLOYEE");
        admin = signed("m08-admin@example.com"); employee = signed("m08-employee@example.com");
        engineer = signed("m08-engineer@example.com"); stranger = signed("m08-stranger@example.com");
    }

    UUID active(String email, String role) {
        var user = new UserEntity(); user.id = UUID.randomUUID(); user.emailNormalized = email; user.name = role;
        user.role = role; user.status = "ACTIVE"; user.passwordHash = passwords.encode(password); user.updatedAt = Instant.now();
        users.insert(user); return user.id;
    }

    HttpResponse<String> call(Browser browser, String method, String path, Object body, String key, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (!method.equals("GET")) {
            request.header("Origin", origin);
            if (browser.csrf() != null) request.header("X-CSRF-Token", browser.csrf());
        }
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        if (version != null) request.header("If-Match", version);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return browser.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    String origin = "http://localhost:5173";
    final String root = "/api/v1/knowledge-articles";
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    Browser signed(String email) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var anon = new Browser(client, null);
        String csrf = data(call(anon, "GET", "/api/v1/auth/csrf", null, null, null)).path("csrfToken").asText();
        var login = call(new Browser(client, csrf), "POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null, null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Browser(client, data(login).path("csrfToken").asText());
    }
    Map<String, Object> body(String title) { return Map.of("title", title, "content", "VPN <script>alert(1)</script> 100% _ \\", "category", "网络"); }
    UUID article() throws Exception {
        var response = call(engineer, "POST", root, body("VPN 指南"), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        assertThat(response.headers().firstValue("ETag")).contains("\"0\"");
        return UUID.fromString(data(response).path("resourceId").asText());
    }
    JsonNode get(Browser actor, String suffix) throws Exception {
        var response = call(actor, "GET", root + suffix, null, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return data(response);
    }
    HttpResponse<String> change(Browser actor, UUID id, String action, long version) throws Exception {
        return call(actor, "POST", root + "/" + id + "/" + action, null, UUID.randomUUID().toString(), "\"" + version + "\"");
    }
    void ok(HttpResponse<String> response, long version) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(data(response).path("acceptedVersion").asLong()).isEqualTo(version);
        assertThat(response.headers().firstValue("ETag")).contains("\"" + version + "\"");
    }
    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo(code);
    }

    @Test
    void fullLifecyclePreservesAuthorAndPlainText() throws Exception {
        UUID id = article(); var draft = get(engineer, "/" + id);
        assertThat(draft.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft.path("authorId").asText()).isEqualTo(engineerId.toString());
        assertThat(draft.path("content").asText()).contains("<script>");
        assertThat(draft.path("publishedAt").isNull()).isTrue();
        assertThat(get(employee, "").path("total").asInt()).isZero();
        clock.advance(5); ok(change(admin, id, "publish", 0), 1);
        assertThat(get(employee, "/" + id).path("publishedAt").asText()).isEqualTo(clock.instant().toString());
        error(call(engineer, "PATCH", root + "/" + id, body("changed"), UUID.randomUUID().toString(), "\"1\""), 409, "INVALID_STATE");
        error(change(admin, id, "publish", 1), 409, "INVALID_STATE");
        ok(change(admin, id, "unpublish", 1), 2);
        assertThat(get(engineer, "/" + id).path("publishedAt").isNull()).isTrue();
        ok(call(engineer, "PATCH", root + "/" + id, body("更新 VPN"), UUID.randomUUID().toString(), "\"2\""), 3);
        clock.advance(5); ok(change(admin, id, "publish", 3), 4);
        assertThat(get(employee, "/" + id).path("title").asText()).isEqualTo("更新 VPN");
    }

    @Test
    void rolesHideDraftsAndDemotionRemovesAuthorAccess() throws Exception {
        UUID id = article(); active("m08-other@example.com", "ENGINEER"); var other = signed("m08-other@example.com");
        for (var actor : List.of(employee, stranger, other))
            error(call(actor, "GET", root + "/" + id, null, null, null), 404, "RESOURCE_NOT_FOUND");
        assertThat(get(admin, "?view=ALL").path("total").asInt()).isEqualTo(1);
        assertThat(get(engineer, "?view=MINE").path("total").asInt()).isEqualTo(1);
        for (var actor : List.of(employee, admin)) {
            error(call(actor, "POST", root, body("forbidden"), UUID.randomUUID().toString(), null), 403, "FORBIDDEN");
            error(call(actor, "GET", root + "?view=MINE", null, null, null), 403, "FORBIDDEN");
        }
        error(change(engineer, id, "publish", 0), 403, "FORBIDDEN");
        error(call(engineer, "GET", root + "?view=ALL", null, null, null), 403, "FORBIDDEN");
        error(call(admin, "PATCH", root + "/" + id, body("admin edit"), UUID.randomUUID().toString(), "\"0\""), 403, "FORBIDDEN");
        ok(change(admin, id, "publish", 0), 1);
        error(call(other, "PATCH", root + "/" + id, body("other edit"), UUID.randomUUID().toString(), "\"1\""), 403, "FORBIDDEN");
        ok(change(admin, id, "unpublish", 1), 2);
        db.update("UPDATE app_user SET role='EMPLOYEE',auth_version=auth_version+1 WHERE id=?", engineerId);
        assertThat(call(engineer, "GET", root + "/" + id, null, null, null).statusCode()).isEqualTo(401);
        engineer = signed("m08-engineer@example.com");
        error(call(engineer, "GET", root + "/" + id, null, null, null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void idempotencyReplaysOriginalOutcomeWithoutRepeatingTransition() throws Exception {
        String key = UUID.randomUUID().toString(); var created = call(engineer, "POST", root, body("original"), key, null);
        UUID id = UUID.fromString(data(created).path("resourceId").asText());
        var replay = call(engineer, "POST", root, body("original"), key, null);
        assertThat(replay.statusCode()).isEqualTo(201); assertThat(data(replay).path("replayed").asBoolean()).isTrue();
        assertThat(data(replay).path("resourceId")).isEqualTo(data(created).path("resourceId"));
        assertThat(call(engineer, "POST", root, body("changed"), key, null).statusCode()).isEqualTo(409);
        String publishKey = UUID.randomUUID().toString();
        ok(call(admin, "POST", root + "/" + id + "/publish", null, publishKey, "\"0\""), 1);
        ok(change(admin, id, "unpublish", 1), 2);
        var old = call(admin, "POST", root + "/" + id + "/publish", null, publishKey, "\"0\"");
        ok(old, 1); assertThat(data(old).path("replayed").asBoolean()).isTrue();
        assertThat(get(admin, "/" + id).path("status").asText()).isEqualTo("DRAFT");
        error(change(admin, id, "publish", 0), 409, "VERSION_CONFLICT");
        assertThat(db.queryForObject("SELECT count(*) FROM knowledge_article", Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentChangesHaveOneWinnerAndFailedInsertRollsBack() throws Exception {
        UUID id = article(); var latch = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> { latch.await(); return change(admin, id, "publish", 0); });
            var second = executor.submit(() -> { latch.await(); return change(admin, id, "publish", 0); });
            latch.countDown(); assertThat(List.of(first.get().statusCode(), second.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        String key = UUID.randomUUID().toString(); long before = db.queryForObject("SELECT count(*) FROM idempotency_record", Long.class);
        db.execute("ALTER TABLE knowledge_article ADD CONSTRAINT m08_test_reject CHECK (title <> 'rollback')");
        try {
            assertThat(call(engineer, "POST", root, body("rollback"), key, null).statusCode()).isGreaterThanOrEqualTo(500);
            assertThat(db.queryForObject("SELECT count(*) FROM idempotency_record", Long.class)).isEqualTo(before);
        } finally { db.execute("ALTER TABLE knowledge_article DROP CONSTRAINT m08_test_reject"); }
        assertThat(call(engineer, "POST", root, body("rollback"), key, null).statusCode()).isEqualTo(201);
    }

    @Test
    void searchEscapesWildcardsAndPaginatesWithoutDuplicates() throws Exception {
        for (int i = 0; i < 23; i++) { UUID id = article(); ok(change(admin, id, "publish", 0), 1); }
        article(); var first = get(employee, "?q=vpn&category=" + java.net.URLEncoder.encode("网络", java.nio.charset.StandardCharsets.UTF_8));
        assertThat(first.path("total").asInt()).isEqualTo(23); assertThat(first.path("pageSize").asInt()).isEqualTo(20);
        assertThat(first.path("items").size()).isEqualTo(20); var second = get(employee, "?page=2");
        assertThat(second.path("items").size()).isEqualTo(3); var ids = new java.util.HashSet<String>();
        first.path("items").forEach(a -> ids.add(a.path("id").asText()));
        second.path("items").forEach(a -> assertThat(ids.add(a.path("id").asText())).isTrue());
        assertThat(get(employee, "?q=%25").path("total").asInt()).isEqualTo(23);
        assertThat(get(employee, "?q=missing%25").path("total").asInt()).isZero();
        assertThat(get(employee, "?q=%5C").path("total").asInt()).isEqualTo(23);
        assertThat(get(engineer, "?view=MINE").path("total").asInt()).isEqualTo(24);
        assertThat(get(admin, "?view=ALL").path("total").asInt()).isEqualTo(24);
        assertThat(get(employee, "?page=999").path("items").isEmpty()).isTrue();
    }

    @Test
    void malformedRequestsDoNotMutateArticles() throws Exception {
        UUID id = article();
        assertThat(call(engineer, "POST", root, body("no key"), null, null).statusCode()).isEqualTo(400);
        assertThat(call(engineer, "PATCH", root + "/" + id, body("missing version"), UUID.randomUUID().toString(), null).statusCode()).isEqualTo(428);
        for (Object invalid : List.of(Map.of("title", " ", "content", "x", "category", "x"), Map.of("title", 1, "content", "x", "category", "x"),
            Map.of("title", "x", "category", "x"), Map.of("title", "x", "content", "x", "category", "x", "extra", true)))
            assertThat(call(engineer, "POST", root, invalid, UUID.randomUUID().toString(), null).statusCode()).isEqualTo(400);
        assertThat(call(admin, "POST", root + "/" + id + "/publish", Map.of("extra", true), UUID.randomUUID().toString(), "\"0\"").statusCode()).isEqualTo(400);
        for (String query : List.of("?page=0", "?page=abc", "?view=OTHER", "?q=", "?category=", "?q=" + "x".repeat(101)))
            error(call(employee, "GET", root + query, null, null, null), 400, "INVALID_QUERY");
        assertThat(db.queryForObject("SELECT version FROM knowledge_article WHERE id=?", Long.class, id)).isZero();
    }

    @Test
    void readsNeverTouchSessionsAndRequireAuthentication() throws Exception {
        UUID id = article(); ok(change(admin, id, "publish", 0), 1);
        var sessions = db.queryForList("SELECT * FROM auth_session ORDER BY token_hash");
        long keys = db.queryForObject("SELECT count(*) FROM idempotency_record", Long.class);
        assertThat(get(employee, "").path("items").get(0)).isEqualTo(get(employee, "/" + id));
        assertThat(db.queryForList("SELECT * FROM auth_session ORDER BY token_hash")).isEqualTo(sessions);
        assertThat(db.queryForObject("SELECT count(*) FROM idempotency_record", Long.class)).isEqualTo(keys);
        assertThat(call(new Browser(HttpClient.newHttpClient(), null), "GET", root, null, null, null).statusCode()).isEqualTo(401);
        error(call(admin, "GET", root + "/" + UUID.randomUUID(), null, null, null), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void v8UpgradePreservesUsers() {
        String schema = "m08_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("8").load().migrate(); UUID id = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'old@example.com','Old','EMPLOYEE','INVITED')", id);
        var before = db.queryForMap("SELECT * FROM " + schema + ".app_user WHERE id=?", id);
        assertThat(config.target("9").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".app_user WHERE id=?", id)).isEqualTo(before);
        assertThat(config.target("9").load().migrate().migrationsExecuted).isZero();
    }

    @AfterAll
    void verifyPackagedApplicationAndOptionallyHoldForBrowser() throws Exception {
        if (!"true".equals(System.getenv("OPENITSM_M08_PACKAGED_VERIFY"))) return;
        if (System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL") != null)
            throw new IllegalStateException("Packaged verification requires the isolated Testcontainers database");
        setUp();
        int originalPort = port; origin = "http://localhost:5179";
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = Path.of("target/m08-packaged-app.log").toAbsolutePath();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            "target/backend-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local", "--server.port=" + port,
            "--spring.datasource.url=" + M01IntegrationTest.PG.getJdbcUrl(),
            "--spring.datasource.username=" + M01IntegrationTest.PG.getUsername(),
            "--spring.datasource.password=" + M01IntegrationTest.PG.getPassword(),
            "--openitsm.account-mail.enabled=false", "--openitsm.ticket-mail.enabled=false", "--openitsm.cookie-secure=false",
            "--openitsm.cookie-name=openitsm-test-session", "--openitsm.origin=http://localhost:5179",
            "--openitsm.attachments.root=" + Path.of("target/m08-attachments").toAbsolutePath(),
            "--openitsm.account-mail-key-file=" + M01IntegrationTest.KEY_FILE)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean ready = false;
            var probe = new Browser(HttpClient.newHttpClient(), null);
            for (int i = 0; i < 160 && process.isAlive(); i++) {
                try { ready = call(probe, "GET", "/health/ready", null, null, null).statusCode() == 200; }
                catch (java.io.IOException ignored) { }
                if (ready) break;
                Thread.sleep(250);
            }
            assertThat(ready).as("Packaged application readiness; see " + log).isTrue();
            assertThat(call(probe, "GET", "/health/live", null, null, null).statusCode()).isEqualTo(200);
            employee = signed("m08-employee@example.com"); engineer = signed("m08-engineer@example.com"); admin = signed("m08-admin@example.com");
            UUID id = article(); ok(change(admin, id, "publish", 0), 1);
            assertThat(get(employee, "/" + id).path("status").asText()).isEqualTo("PUBLISHED");
            ok(change(admin, id, "unpublish", 1), 2);
            error(call(employee, "GET", root + "/" + id, null, null, null), 404, "RESOURCE_NOT_FOUND");
            ok(call(engineer, "PATCH", root + "/" + id, body("独立 JAR 知识验证"), UUID.randomUUID().toString(), "\"2\""), 3);
            ok(change(admin, id, "publish", 3), 4);
            System.out.println("M08_PACKAGED_VERIFIED port=" + port + " article=" + id);
            String hold = System.getenv("OPENITSM_M08_HOLD_SECONDS");
            if (hold != null) {
                System.out.println("M08_BROWSER_READY");
                Path release = Path.of(System.getenv().getOrDefault("OPENITSM_M08_RELEASE_FILE", "target/m08-browser-release"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(1200, Math.max(0, Integer.parseInt(hold))));
                while (System.nanoTime() < deadline && !java.nio.file.Files.exists(release)) Thread.sleep(250);
            }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            port = originalPort; origin = "http://localhost:5173";
        }
    }
}
