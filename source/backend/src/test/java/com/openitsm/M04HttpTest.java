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
class M04HttpTest {
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) { M01IntegrationTest.properties(props); }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired UserMapper users;
    @Autowired BootstrapService bootstrap;
    @Autowired PasswordEncoder passwords;
    @Autowired PlatformTransactionManager transactions;
    Browser admin, employee, engineer, stranger;
    UUID employeeId, engineerId;
    final String password = "TestPassword1234";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach
    void setUp() throws Exception {
        db.execute("TRUNCATE idempotency_record,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        bootstrap.initialize("M04 Admin", "m04-admin@example.com", password);
        employeeId = active("m04-employee@example.com", "EMPLOYEE");
        engineerId = active("m04-engineer@example.com", "ENGINEER");
        active("m04-stranger@example.com", "EMPLOYEE");
        admin = signed("m04-admin@example.com"); employee = signed("m04-employee@example.com");
        engineer = signed("m04-engineer@example.com"); stranger = signed("m04-stranger@example.com");
    }

    UUID active(String email, String role) {
        var user = new UserEntity(); user.id = UUID.randomUUID(); user.emailNormalized = email; user.name = role;
        user.role = role; user.status = "ACTIVE"; user.passwordHash = passwords.encode(password); user.updatedAt = Instant.now();
        users.insert(user); return user.id;
    }

    HttpResponse<String> call(Browser browser, String method, String path, Object body, String key, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (!method.equals("GET")) {
            request.header("Origin", "http://localhost:5173");
            if (browser.csrf() != null) request.header("X-CSRF-Token", browser.csrf());
        }
        if (body != null) request.header("Content-Type", "application/json");
        if (key != null) request.header("Idempotency-Key", key);
        if (version != null) request.header("If-Match", version);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return browser.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    Browser signed(String email) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var anon = new Browser(client, null);
        String csrf = data(call(anon, "GET", "/api/v1/auth/csrf", null, null, null)).path("csrfToken").asText();
        var login = call(new Browser(client, csrf), "POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null, null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Browser(client, data(login).path("csrfToken").asText());
    }
    UUID ticket() throws Exception {
        var created = call(employee, "POST", "/api/v1/tickets", Map.of("title", "M04 ticket", "description", "Communication",
            "businessType", "IT", "impactScope", "Test"), UUID.randomUUID().toString(), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        return UUID.fromString(data(created).path("id").asText());
    }
    String path(UUID id, String action) { return "/api/v1/tickets/" + id + "/" + action; }
    HttpResponse<String> post(Browser browser, UUID id, String action, Object body, long version) throws Exception {
        return call(browser, "POST", path(id, action), body, UUID.randomUUID().toString(), "\"" + version + "\"");
    }
    JsonNode page(Browser browser, UUID id, String action) throws Exception {
        var response = call(browser, "GET", path(id, action), null, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return data(response);
    }
    void ok(HttpResponse<String> response, long version) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(data(response).path("acceptedVersion").asLong()).isEqualTo(version);
        assertThat(response.headers().firstValue("ETag")).contains("\"" + version + "\"");
    }
    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var root = json.readTree(response.body());
        assertThat(root.path("error").path("code").asText()).isEqualTo(code);
        assertThat(root.path("error").path("fieldErrors").isArray()).isTrue();
        assertThat(root.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }
    long count(String table) {
        return db.queryForObject("SELECT count(*) FROM " + table
            + (table.equals("audit_event") ? " WHERE object_type='TICKET'" : ""), Long.class);
    }
    long version(UUID id) { return db.queryForObject("SELECT version FROM ticket WHERE id=?", Long.class, id); }

    @Test
    void rolesStatesAndInternalVisibilityUseRealPersistedReplies() throws Exception {
        for (String state : List.of("NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED")) {
            UUID id = ticket();
            db.update("UPDATE ticket SET status=? WHERE id=?", state, id);
            ok(post(employee, id, "replies", Map.of("content", "  employee <script>text</script>  "), 0), 1);
            ok(post(engineer, id, "internal-notes", Map.of("content", "private marker"), 1), 2);
            ok(post(admin, id, "internal-notes", Map.of("content", "admin private"), 2), 3);
            ok(post(engineer, id, "replies", Map.of("content", "public engineer"), 3), 4);
            ok(post(admin, id, "replies", Map.of("content", "public admin"), 4), 5);
            var visible = page(employee, id, "replies");
            assertThat(visible.path("total").asInt()).isEqualTo(3);
            assertThat(visible.toString()).doesNotContain("private", "INTERNAL", "password", "email");
            assertThat(visible.path("items").get(0).path("content").asText()).isEqualTo("employee <script>text</script>");
            assertThat(visible.path("items").get(0).path("authorId").asText()).isEqualTo(employeeId.toString());
            assertThat(page(engineer, id, "replies").path("total").asInt()).isEqualTo(5);
            assertThat(page(admin, id, "replies").path("total").asInt()).isEqualTo(5);
            assertThat(page(employee, id, "operations").path("total").asInt()).isEqualTo(3);
            assertThat(page(engineer, id, "operations").path("total").asInt()).isEqualTo(5);
            assertThat(db.queryForObject("SELECT status FROM ticket WHERE id=?", String.class, id)).isEqualTo(state);
            assertThat(version(id)).isEqualTo(5);
        }
    }

    @Test
    void accessChecksAndClosedStateRejectWithoutWrites() throws Exception {
        UUID id = ticket();
        for (String action : List.of("replies", "internal-notes")) {
            error(post(stranger, id, action, Map.of("content", "forbidden"), 0), 404, "RESOURCE_NOT_FOUND");
            error(post(employee, UUID.randomUUID(), action, Map.of("content", "missing"), 0), 404, "RESOURCE_NOT_FOUND");
        }
        for (String action : List.of("replies", "operations")) {
            error(call(stranger, "GET", path(id, action), null, null, null), 404, "RESOURCE_NOT_FOUND");
            error(call(admin, "GET", path(UUID.randomUUID(), action), null, null, null), 404, "RESOURCE_NOT_FOUND");
        }
        error(post(employee, id, "internal-notes", Map.of("content", "forbidden"), 0), 403, "FORBIDDEN");
        db.update("UPDATE ticket SET status='CLOSED' WHERE id=?", id);
        for (Browser actor : List.of(employee, engineer, admin))
            error(post(actor, id, "replies", Map.of("content", "closed"), 0), 409, "INVALID_STATE");
        error(post(engineer, id, "internal-notes", Map.of("content", "closed"), 0), 409, "INVALID_STATE");
        assertThat(page(employee, id, "replies").path("total").asInt()).isZero();
        assertThat(count("ticket_reply")).isZero(); assertThat(count("audit_event")).isZero();
        assertThat(count("idempotency_record")).isEqualTo(1); assertThat(version(id)).isZero();
    }

    @Test
    void versionAndIdempotencyPreserveOriginalResultAndScope() throws Exception {
        UUID id = ticket(); String key = UUID.randomUUID().toString();
        var body = Map.of("content", "original");
        ok(call(employee, "POST", path(id, "replies"), body, key, "\"0\""), 1);
        ok(post(engineer, id, "replies", Map.of("content", "later"), 1), 2);
        db.update("UPDATE ticket SET status='CLOSED' WHERE id=?", id);
        var replay = call(employee, "POST", path(id, "replies"), body, key, "\"0\"");
        ok(replay, 1);
        assertThat(data(replay).path("resourceId").asText()).isEqualTo(id.toString());
        assertThat(data(replay).path("replayed").asBoolean()).isTrue();
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        error(call(employee, "POST", path(id, "replies"), Map.of("content", " original "), key, "\"0\""), 409, "IDEMPOTENCY_CONFLICT");
        error(call(employee, "POST", path(id, "replies"), body, key, "\"1\""), 409, "IDEMPOTENCY_CONFLICT");
        error(call(employee, "POST", path(id, "replies"), body, key, null), 428, "PRECONDITION_REQUIRED");
        error(call(employee, "POST", path(id, "replies"), body, key, "invalid"), 400, "VALIDATION_FAILED");
        error(post(employee, id, "replies", body, 0), 409, "VERSION_CONFLICT");
        assertThat(count("ticket_reply")).isEqualTo(2); assertThat(count("audit_event")).isEqualTo(2);
        assertThat(version(id)).isEqualTo(2);
        UUID second = ticket();
        ok(call(employee, "POST", path(second, "replies"), body, key, "\"0\""), 1);
        ok(call(engineer, "POST", path(second, "replies"), body, key, "\"1\""), 2);
        ok(call(engineer, "POST", path(second, "internal-notes"), body, key, "\"2\""), 3);
    }

    @Test
    void invalidInputSessionAndCsrfFollowExistingContract() throws Exception {
        UUID id = ticket();
        for (Object body : List.of(Map.of(), Map.of("content", " \n\t"), Map.of("content", 42),
            Map.of("content", "ok", "visibility", "PUBLIC"), java.util.Collections.singletonMap("content", null)))
            error(post(employee, id, "replies", body, 0), 400, "VALIDATION_FAILED");
        error(post(employee, id, "replies", null, 0), 400, "VALIDATION_FAILED");
        error(post(employee, id, "replies", Map.of("content", "x".repeat(256 * 1024)), 0), 413, "REQUEST_TOO_LARGE");
        error(call(employee, "POST", path(id, "replies"), Map.of("content", "ok"), null, "\"0\""), 400, "VALIDATION_FAILED");
        error(call(employee, "POST", path(id, "replies"), Map.of("content", "ok"), "invalid", "\"0\""), 400, "VALIDATION_FAILED");
        var anon = new Browser(HttpClient.newHttpClient(), null);
        error(call(anon, "GET", path(id, "replies"), null, null, null), 401, "SESSION_EXPIRED");
        error(post(anon, id, "replies", Map.of("content", "ok"), 0), 401, "SESSION_EXPIRED");
        error(post(new Browser(employee.client(), "x".repeat(64)), id, "replies", Map.of("content", "ok"), 0), 403, "CSRF_INVALID");
        db.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", engineerId);
        error(post(engineer, id, "internal-notes", Map.of("content", "old session"), 0), 401, "SESSION_EXPIRED");
        assertThat(count("ticket_reply")).isZero(); assertThat(count("audit_event")).isZero();
        assertThat(version(id)).isZero();
    }

    @Test
    void paginationAndAuditProjectionFilterBeforeCountingAndDoNotTouchSession() throws Exception {
        UUID id = ticket();
        ok(post(admin, id, "priority-changes", Map.of("priority", "P1", "reason", "internal reason marker"), 0), 1);
        db.update("UPDATE audit_event SET before_after=before_after || '{\"secret\":\"raw audit marker\"}'::jsonb WHERE object_id=?", id);
        for (int i = 0; i < 23; i++) {
            ok(post(employee, id, "replies", Map.of("content", "public " + i), i * 2L + 1), i * 2L + 2);
            ok(post(engineer, id, "internal-notes", Map.of("content", "private " + i), i * 2L + 2), i * 2L + 3);
        }
        // Equal timestamps exercise the UUID tie breaker independently of insertion order.
        db.execute("UPDATE ticket_reply SET created_at=TIMESTAMPTZ '2026-10-16 00:00:00+00'");
        db.execute("UPDATE audit_event SET created_at=TIMESTAMPTZ '2026-10-16 00:00:00+00'");
        long auditCount = count("audit_event"), idemCount = count("idempotency_record");
        var activity = db.queryForList("SELECT token_hash,last_user_activity_at FROM auth_session ORDER BY token_hash");
        for (String action : List.of("replies", "operations")) {
            var first = page(employee, id, action);
            assertThat(first.path("total").asInt()).isEqualTo(action.equals("replies") ? 23 : 24);
            assertThat(first.path("items").size()).isEqualTo(20);
            var second = page(employee, id, action + "?page=2");
            assertThat(second.path("items").size()).isEqualTo(action.equals("replies") ? 3 : 4);
            var ids = new java.util.ArrayList<String>();
            first.path("items").forEach(item -> ids.add(item.path("id").asText()));
            second.path("items").forEach(item -> ids.add(item.path("id").asText()));
            assertThat(ids).isSorted().doesNotHaveDuplicates();
            assertThat(first.toString() + second).doesNotContain("private", "INTERNAL", "internal reason marker", "raw audit marker", "requestId", "beforeAfter");
            assertThat(page(employee, id, action + "?page=99").path("items").isEmpty()).isTrue();
            for (String invalid : List.of("0", "-1", "abc", "2147483648", ""))
                error(call(employee, "GET", path(id, action + "?page=" + invalid), null, null, null), 400, "INVALID_QUERY");
        }
        assertThat(page(engineer, id, "replies").path("total").asInt()).isEqualTo(46);
        assertThat(page(engineer, id, "operations").path("total").asInt()).isEqualTo(47);
        var staffEvents = page(admin, id, "operations").toString()
            + page(admin, id, "operations?page=2") + page(admin, id, "operations?page=3");
        assertThat(staffEvents).contains("internal reason marker").doesNotContain("raw audit marker");
        assertThat(count("audit_event")).isEqualTo(auditCount); assertThat(count("idempotency_record")).isEqualTo(idemCount);
        assertThat(db.queryForList("SELECT token_hash,last_user_activity_at FROM auth_session ORDER BY token_hash")).isEqualTo(activity);
    }

    @Test
    void competingRepliesAndClosureRespectTicketVersion() throws Exception {
        UUID id = ticket(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return post(employee, id, "replies", Map.of("content", "one"), 0); });
            var two = pool.submit(() -> { start.await(); return post(engineer, id, "internal-notes", Map.of("content", "two"), 0); });
            start.countDown();
            var results = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 409);
            error(results.stream().filter(r -> r.statusCode() == 409).findFirst().orElseThrow(), 409, "VERSION_CONFLICT");
        }
        assertThat(count("ticket_reply")).isEqualTo(1); assertThat(count("audit_event")).isEqualTo(1); assertThat(version(id)).isEqualTo(1);
        UUID resolved = ticket(); db.update("UPDATE ticket SET status='RESOLVED' WHERE id=?", resolved);
        var race = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var close = pool.submit(() -> { race.await(); return post(admin, resolved, "close", Map.of("reason", "confirmed"), 0); });
            var reply = pool.submit(() -> { race.await(); return post(engineer, resolved, "replies", Map.of("content", "racing"), 0); });
            race.countDown();
            assertThat(List.of(close.get(25, TimeUnit.SECONDS).statusCode(), reply.get(25, TimeUnit.SECONDS).statusCode()))
                .containsExactlyInAnyOrder(200, 409);
        }
        assertThat(version(resolved)).isEqualTo(1);
    }

    @Test
    void inProgressKeyIsRetryableAndDuplicateConcurrentRequestsNeverDuplicateReply() throws Exception {
        UUID id = ticket(); String key = UUID.randomUUID().toString();
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var lock = pool.submit(() -> new TransactionTemplate(transactions).execute(status -> {
                db.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?,0))", Object.class,
                    "IDEMPOTENCY:" + employeeId + ":POST /api/v1/tickets/" + id + "/replies:" + key);
                held.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return null;
            }));
            try {
                assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();
                error(call(employee, "POST", path(id, "replies"), Map.of("content", "same"), key, "\"0\""), 409, "REQUEST_IN_PROGRESS");
            } finally { release.countDown(); }
            lock.get(15, TimeUnit.SECONDS);
        }
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return call(employee, "POST", path(id, "replies"), Map.of("content", "same"), key, "\"0\""); });
            var two = pool.submit(() -> { start.await(); return call(employee, "POST", path(id, "replies"), Map.of("content", "same"), key, "\"0\""); });
            start.countDown();
            var results = List.of(one.get(25, TimeUnit.SECONDS), two.get(25, TimeUnit.SECONDS));
            assertThat(results.stream().filter(r -> r.statusCode() == 200).count()).isGreaterThanOrEqualTo(1);
            for (var response : results) if (response.statusCode() != 200) error(response, 409, "REQUEST_IN_PROGRESS");
        }
        ok(call(employee, "POST", path(id, "replies"), Map.of("content", "same"), key, "\"0\""), 1);
        assertThat(count("ticket_reply")).isEqualTo(1); assertThat(count("audit_event")).isEqualTo(1);
        assertThat(count("idempotency_record")).isEqualTo(2); assertThat(version(id)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackReplyVersionAndIdempotency() throws Exception {
        UUID id = ticket();
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT m04_test_reject CHECK (action <> 'TICKET_REPLY')");
        try {
            var response = post(employee, id, "replies", Map.of("content", "must rollback"), 0);
            assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
            assertThat(count("ticket_reply")).isZero(); assertThat(count("audit_event")).isZero();
            assertThat(count("idempotency_record")).isEqualTo(1); assertThat(version(id)).isZero();
        } finally { db.execute("ALTER TABLE audit_event DROP CONSTRAINT m04_test_reject"); }
        ok(post(employee, id, "replies", Map.of("content", "recovered"), 0), 1);
    }

    @Test
    void v4UpgradePreservesTicketsAndExistingOperations() {
        String schema = "m04_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("4").load().migrate();
        UUID userId = UUID.randomUUID(), id = UUID.randomUUID(), eventId = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'upgrade@example.com','Upgrade','EMPLOYEE','INVITED')", userId);
        db.update("INSERT INTO " + schema + ".ticket(id,title,description,business_type,impact_scope,requester_id,version) VALUES (?,'Original','Description','IT','Test',?,7)", id, userId);
        db.update("INSERT INTO " + schema + ".audit_event(id,actor_id,action,object_type,object_id,before_after,outcome,request_id,created_at) VALUES (?,?,'TICKET_CLAIM','TICKET',?,'{}','SUCCESS',?,CURRENT_TIMESTAMP)", eventId, userId, id, UUID.randomUUID());
        var ticketBefore = db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id);
        var auditBefore = db.queryForMap("SELECT * FROM " + schema + ".audit_event WHERE id=?", eventId);
        assertThat(config.target("5").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id)).isEqualTo(ticketBefore);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".audit_event WHERE id=?", eventId)).isEqualTo(auditBefore);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket_reply", Long.class)).isZero();
        assertThat(config.target("5").load().migrate().migrationsExecuted).isZero();
    }

    @AfterAll
    void verifyPackagedApplicationWhenRequested() throws Exception {
        String hold = System.getenv("OPENITSM_M04_HOLD_SECONDS");
        if (hold != null) {
            setUp();
            System.out.println("M04_BROWSER_FIXTURE_READY " + M01IntegrationTest.PG.getJdbcUrl()
                + " KEY_FILE=" + M01IntegrationTest.KEY_FILE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(1200, Math.max(0, Integer.parseInt(hold))));
            String releaseFile = System.getenv("OPENITSM_M04_RELEASE_FILE");
            while (System.nanoTime() < deadline && (releaseFile == null || !java.nio.file.Files.exists(Path.of(releaseFile))))
                Thread.sleep(250);
        }
        if (!"true".equals(System.getenv("OPENITSM_M04_PACKAGED_VERIFY"))) return;
        setUp();
        int originalPort = port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = Path.of("target/m04-packaged-app.log").toAbsolutePath();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            "target/backend-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local", "--server.port=" + port,
            "--spring.datasource.url=" + M01IntegrationTest.PG.getJdbcUrl(),
            "--spring.datasource.username=" + M01IntegrationTest.PG.getUsername(),
            "--spring.datasource.password=" + M01IntegrationTest.PG.getPassword(),
            "--openitsm.account-mail.enabled=false", "--openitsm.cookie-secure=false",
            "--openitsm.cookie-name=openitsm-test-session", "--openitsm.origin=http://localhost:5173",
            "--openitsm.account-mail-key-file=" + M01IntegrationTest.KEY_FILE)
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            boolean ready = false;
            var probe = new Browser(HttpClient.newHttpClient(), null);
            for (int i = 0; i < 120 && process.isAlive(); i++) {
                try { ready = call(probe, "GET", "/health/ready", null, null, null).statusCode() == 200; }
                catch (java.io.IOException ignored) { }
                if (ready) break;
                Thread.sleep(250);
            }
            assertThat(ready).as("Packaged application readiness; see " + log).isTrue();
            assertThat(call(probe, "GET", "/health/live", null, null, null).statusCode()).isEqualTo(200);
            employee = signed("m04-employee@example.com"); engineer = signed("m04-engineer@example.com"); admin = signed("m04-admin@example.com");
            UUID id = ticket();
            var body = Map.of("content", "packaged public reply"); String key = UUID.randomUUID().toString();
            ok(call(employee, "POST", path(id, "replies"), body, key, "\"0\""), 1);
            ok(call(employee, "POST", path(id, "replies"), body, key, "\"0\""), 1);
            ok(post(engineer, id, "internal-notes", Map.of("content", "packaged private note"), 1), 2);
            assertThat(page(employee, id, "replies").path("total").asInt()).isEqualTo(1);
            assertThat(page(admin, id, "replies").path("total").asInt()).isEqualTo(2);
            assertThat(page(employee, id, "operations").path("total").asInt()).isEqualTo(1);
            assertThat(page(admin, id, "operations").path("total").asInt()).isEqualTo(2);
            error(post(employee, id, "internal-notes", body, 2), 403, "FORBIDDEN");
            assertThat(count("ticket_reply")).isEqualTo(2); assertThat(count("audit_event")).isEqualTo(2);
            System.out.println("M04_PACKAGED_VERIFIED: live/ready=UP, V5, four endpoints, roles, replay, version=2, replies=2, audit=2");
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            port = originalPort;
        }
    }
}
