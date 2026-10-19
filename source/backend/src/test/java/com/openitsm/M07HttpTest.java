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
class M07HttpTest {
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
        bootstrap.initialize("M07 Admin", "m07-admin@example.com", password);
        employeeId = active("m07-employee@example.com", "EMPLOYEE");
        engineerId = active("m07-engineer@example.com", "ENGINEER");
        active("m07-stranger@example.com", "EMPLOYEE");
        admin = signed("m07-admin@example.com"); employee = signed("m07-employee@example.com");
        engineer = signed("m07-engineer@example.com"); stranger = signed("m07-stranger@example.com");
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
        var created = call(employee, "POST", "/api/v1/tickets", Map.of("title", "M07 ticket", "description", "Communication",
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


    JsonNode detail(Browser browser, UUID id) throws Exception {
        var response = call(browser, "GET", "/api/v1/tickets/" + id, null, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return data(response);
    }
    JsonNode sla(UUID id) throws Exception { return detail(employee, id).path("sla"); }
    void change(Browser browser, UUID id, String action, Object body) throws Exception {
        ok(post(browser, id, action, body, version(id)), version(id));
    }
    void priority(UUID id, String priority) throws Exception {
        change(admin, id, "priority-changes", Map.of("priority", priority, "reason", "SLA test"));
    }

    @Test
    void fourPrioritiesUseNaturalTimeAndStrictDeadlineBoundaries() throws Exception {
        int[] responseMinutes = {15, 60, 240, 480}, resolutionHours = {4, 8, 24, 72};
        for (int i = 0; i < 4; i++) {
            clock.now = clock.instant().plusSeconds(60).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
            employee = signed("m07-employee@example.com"); admin = signed("m07-admin@example.com");
            UUID id = ticket(); priority(id, "P" + (i + 1));
            Instant start = clock.instant(), responseDue = start.plusSeconds(responseMinutes[i] * 60L);
            Instant resolutionDue = start.plusSeconds(resolutionHours[i] * 3600L);
            assertThat(sla(id).path("responseDueAt").asText()).isEqualTo(responseDue.toString());
            assertThat(sla(id).path("resolutionDueAt").asText()).isEqualTo(resolutionDue.toString());
            clock.now = responseDue; employee = signed("m07-employee@example.com");
            assertThat(sla(id).path("responseOverdue").asBoolean()).isFalse();
            clock.now = responseDue.plusNanos(1000);
            assertThat(sla(id).path("responseOverdue").asBoolean()).isTrue();
            clock.now = resolutionDue; employee = signed("m07-employee@example.com");
            assertThat(sla(id).path("resolutionOverdue").asBoolean()).isFalse();
            clock.now = resolutionDue.plusNanos(1000);
            assertThat(sla(id).path("resolutionOverdue").asBoolean()).isTrue();
        }
    }

    @Test
    void onlyPublicStaffTextCountsIncludingOwnTicketsAndRoleAtWriteTime() throws Exception {
        UUID id = ticket();
        change(engineer, id, "claim", null);
        change(employee, id, "replies", Map.of("content", "requester text"));
        change(engineer, id, "internal-notes", Map.of("content", "private"));
        assertThat(sla(id).path("firstRespondedAt").isNull()).isTrue();
        clock.advance(60);
        change(admin, id, "replies", Map.of("content", " first public response "));
        String first = clock.instant().toString(); clock.advance(60);
        change(engineer, id, "replies", Map.of("content", "later"));
        assertThat(sla(id).path("firstRespondedAt").asText()).isEqualTo(first);
        // Later role changes must never rewrite the captured response timestamp.
        db.update("UPDATE app_user SET role='EMPLOYEE' WHERE email_normalized='m07-admin@example.com'");
        assertThat(sla(id).path("firstRespondedAt").asText()).isEqualTo(first);
        for (Browser actor : List.of(engineer, signed("m07-admin@example.com"))) {
            // Restore administrator role for the second own-ticket check.
            db.update("UPDATE app_user SET role='ADMIN' WHERE email_normalized='m07-admin@example.com'");
            var created = call(actor, "POST", "/api/v1/tickets", Map.of("title", "Own", "description", "Own",
                "businessType", "IT", "impactScope", "Test"), UUID.randomUUID().toString(), null);
            assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
            UUID own = UUID.fromString(data(created).path("id").asText());
            change(actor, own, "replies", Map.of("content", "own public response"));
            assertThat(detail(actor, own).path("sla").path("firstRespondedAt").asText()).isEqualTo(clock.instant().toString());
        }
    }

    @Test
    void suspensionTransferAndClosureKeepCycleWhileReopenStartsFresh() throws Exception {
        UUID id = ticket(); priority(id, "P1");
        JsonNode initial = sla(id);
        change(engineer, id, "claim", null);
        clock.advance(900);
        change(engineer, id, "replies", Map.of("content", "at response deadline"));
        change(engineer, id, "suspensions", Map.of("reason", "waiting"));
        assertThat(sla(id).path("responseOverdue").asBoolean()).isFalse();
        clock.advance(1);
        assertThat(sla(id).path("resolutionDueAt")).isEqualTo(initial.path("resolutionDueAt"));
        UUID other = active("m07-other-engineer@example.com", "ENGINEER");
        change(admin, id, "transfers", Map.of("assigneeId", other));
        assertThat(sla(id).path("responseDueAt")).isEqualTo(initial.path("responseDueAt"));
        var otherBrowser = signed("m07-other-engineer@example.com");
        change(otherBrowser, id, "resume", null);
        clock.now = Instant.parse(initial.path("resolutionDueAt").asText());
        otherBrowser = signed("m07-other-engineer@example.com");
        change(otherBrowser, id, "resolutions", Map.of("summary", "at resolution deadline"));
        employee = signed("m07-employee@example.com");
        var completed = sla(id);
        assertThat(completed.path("resolutionOverdue").asBoolean()).isFalse();
        clock.advance(1);
        change(employee, id, "close", null);
        assertThat(sla(id)).isEqualTo(completed);
        change(employee, id, "reopen", Map.of("reason", "recurring"));
        var reopened = sla(id);
        assertThat(reopened.path("firstRespondedAt").isNull()).isTrue();
        assertThat(reopened.path("resolvedAt").isNull()).isTrue();
        assertThat(reopened.path("responseDueAt").asText()).isEqualTo(clock.instant().plusSeconds(900).toString());
        assertThat(reopened.path("resolutionDueAt").asText()).isEqualTo(clock.instant().plusSeconds(14400).toString());
        assertThat(reopened.path("responseOverdue").asBoolean()).isFalse();
        assertThat(detail(employee, id).path("resolutionSummary").asText()).isEqualTo("at resolution deadline");
    }

    @Test
    void completedMetricsUseActualTimesAndRecalculateWithNewPriority() throws Exception {
        UUID id = ticket(); change(engineer, id, "claim", null);
        clock.advance(3600);
        change(engineer, id, "replies", Map.of("content", "one hour response"));
        clock.advance(8 * 3600);
        engineer = signed("m07-engineer@example.com");
        change(engineer, id, "resolutions", Map.of("summary", "nine hour resolution"));
        employee = signed("m07-employee@example.com"); admin = signed("m07-admin@example.com");
        var completed = sla(id);
        assertThat(completed.path("responseOverdue").asBoolean()).isFalse();
        assertThat(completed.path("resolutionOverdue").asBoolean()).isFalse();
        priority(id, "P1");
        assertThat(sla(id).path("responseOverdue").asBoolean()).isTrue();
        assertThat(sla(id).path("resolutionOverdue").asBoolean()).isTrue();
        assertThat(sla(id).path("resolvedAt")).isEqualTo(completed.path("resolvedAt"));
        priority(id, "P4"); clock.advance(80 * 3600);
        employee = signed("m07-employee@example.com");
        assertThat(sla(id).path("responseOverdue").asBoolean()).isFalse();
        assertThat(sla(id).path("resolutionOverdue").asBoolean()).isFalse();
    }

    @Test
    void replayConcurrencyAndRollbackPreserveFirstResponse() throws Exception {
        UUID id = ticket(); String key = UUID.randomUUID().toString();
        var body = Map.of("content", "exact original reply");
        ok(call(engineer, "POST", path(id, "replies"), body, key, "\"0\""), 1);
        String first = sla(id).path("firstRespondedAt").asText(); clock.advance(60);
        ok(call(engineer, "POST", path(id, "replies"), body, key, "\"0\""), 1);
        assertThat(sla(id).path("firstRespondedAt").asText()).isEqualTo(first);
        assertThat(count("ticket_reply")).isEqualTo(1);

        UUID concurrent = ticket(); var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> { start.await(); return post(engineer, concurrent, "replies", body, 0); });
            var two = executor.submit(() -> { start.await(); return post(admin, concurrent, "replies", body, 0); });
            start.countDown();
            assertThat(List.of(one.get().statusCode(), two.get().statusCode())).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(sla(concurrent).path("firstRespondedAt").asText()).isEqualTo(clock.instant().toString());
        assertThat(db.queryForObject("SELECT count(*) FROM ticket_reply WHERE ticket_id=?", Long.class, concurrent)).isEqualTo(1);
        UUID rollback = ticket();
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT m07_test_reject CHECK (action <> 'TICKET_REPLY' OR object_id <> '" + rollback + "'::uuid)");
        try {
            assertThat(post(engineer, rollback, "replies", body, 0).statusCode()).isGreaterThanOrEqualTo(500);
            assertThat(sla(rollback).path("firstRespondedAt").isNull()).isTrue();
            assertThat(version(rollback)).isZero();
        } finally { db.execute("ALTER TABLE audit_event DROP CONSTRAINT m07_test_reject"); }
    }

    @Test
    void readsShareSlaEnforceVisibilityAndNeverWriteOrTouchSessions() throws Exception {
        UUID id = ticket();
        var before = db.queryForList("SELECT * FROM auth_session ORDER BY token_hash");
        long events = count("audit_event"), keys = count("idempotency_record");
        var response = call(employee, "GET", "/api/v1/tickets", null, null, null);
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(data(response).path("items").get(0).path("sla")).isEqualTo(sla(id));
        error(call(stranger, "GET", "/api/v1/tickets/" + id, null, null, null), 404, "RESOURCE_NOT_FOUND");
        assertThat(detail(admin, id).path("sla")).isEqualTo(sla(id));
        assertThat(db.queryForList("SELECT * FROM auth_session ORDER BY token_hash")).isEqualTo(before);
        assertThat(count("audit_event")).isEqualTo(events); assertThat(count("idempotency_record")).isEqualTo(keys);
        assertThat(version(id)).isZero();
        db.update("UPDATE ticket SET sla_tracked=false WHERE id=?", id);
        assertThat(detail(employee, id).path("sla").isNull()).isTrue();
    }

    @Test
    void v6UpgradeKeepsOldDataAndDoesNotInventHistoricalSla() {
        String schema = "m07_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("6").load().migrate();
        UUID userId = UUID.randomUUID(), id = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'old@example.com','Old','EMPLOYEE','INVITED')", userId);
        db.update("INSERT INTO " + schema + ".ticket(id,title,description,business_type,impact_scope,requester_id,version) VALUES (?,'Old','Description','IT','Test',?,7)", id, userId);
        var before = db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id);
        assertThat(config.target("7").load().migrate().migrationsExecuted).isEqualTo(1);
        var after = db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id);
        assertThat(after).containsAllEntriesOf(before);
        assertThat(after.get("sla_tracked")).isEqualTo(false);
        assertThat(after.get("first_responded_at")).isNull(); assertThat(after.get("resolved_at")).isNull();
        assertThat(config.target("7").load().migrate().migrationsExecuted).isZero();
    }

    @AfterAll
    void verifyPackagedApplicationAndOptionallyHoldForBrowser() throws Exception {
        if (!"true".equals(System.getenv("OPENITSM_M07_PACKAGED_VERIFY"))) return;
        if (System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL") != null)
            throw new IllegalStateException("Packaged verification requires the isolated Testcontainers database");
        setUp();
        int originalPort = port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = Path.of("target/m07-packaged-app.log").toAbsolutePath();
        var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
            "target/backend-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local", "--server.port=" + port,
            "--spring.datasource.url=" + M01IntegrationTest.PG.getJdbcUrl(),
            "--spring.datasource.username=" + M01IntegrationTest.PG.getUsername(),
            "--spring.datasource.password=" + M01IntegrationTest.PG.getPassword(),
            "--openitsm.account-mail.enabled=false", "--openitsm.cookie-secure=false",
            "--openitsm.cookie-name=openitsm-test-session", "--openitsm.origin=http://localhost:5173",
            "--openitsm.attachments.root=" + Path.of("target/m07-attachments").toAbsolutePath(),
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
            employee = signed("m07-employee@example.com"); engineer = signed("m07-engineer@example.com"); admin = signed("m07-admin@example.com");
            UUID overdue = ticket();
            db.update("UPDATE ticket SET priority='P1',created_at=CURRENT_TIMESTAMP-INTERVAL '5 hours',cycle_started_at=CURRENT_TIMESTAMP-INTERVAL '5 hours' WHERE id=?", overdue);
            assertThat(sla(overdue).path("responseOverdue").asBoolean()).isTrue();
            assertThat(sla(overdue).path("resolutionOverdue").asBoolean()).isTrue();
            UUID completed = ticket();
            change(engineer, completed, "claim", null);
            change(engineer, completed, "replies", Map.of("content", "Packaged first response"));
            change(engineer, completed, "resolutions", Map.of("summary", "Packaged resolution"));
            assertThat(sla(completed).path("firstRespondedAt").isTextual()).isTrue();
            assertThat(sla(completed).path("resolvedAt").isTextual()).isTrue();
            assertThat(sla(completed).path("resolutionOverdue").asBoolean()).isFalse();
            UUID fresh = ticket();
            System.out.println("M07_PACKAGED_VERIFIED port=" + port + " overdue=" + overdue + " completed=" + completed + " fresh=" + fresh);
            String hold = System.getenv("OPENITSM_M07_HOLD_SECONDS");
            if (hold != null) {
                System.out.println("M07_BROWSER_READY");
                Path release = Path.of(System.getenv().getOrDefault("OPENITSM_M07_RELEASE_FILE", "target/m07-browser-release"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(Math.min(1200, Math.max(0, Integer.parseInt(hold))));
                while (System.nanoTime() < deadline && !java.nio.file.Files.exists(release)) Thread.sleep(250);
            }
        } finally {
            process.destroy();
            if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            port = originalPort;
        }
    }
}
