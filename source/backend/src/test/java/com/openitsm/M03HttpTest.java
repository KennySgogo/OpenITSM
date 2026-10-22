package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.dto.TicketReasonRequest;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AuthContext;
import com.openitsm.service.AuthService;
import com.openitsm.service.BootstrapService;
import com.openitsm.service.TicketService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/** Full Spring Boot TCP HTTP + real isolated PostgreSQL; no mocked business services. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Import(M01IntegrationTest.TimeConfig.class)
class M03HttpTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.openitsm.config.AccountMailSchedulingConfig mailScheduler;

    @AfterAll
    static void holdForPackagedApplicationVerification() throws InterruptedException {
        String hold = System.getenv("OPENITSM_M03_HOLD_SECONDS");
        if (hold != null) {
            System.out.println("M03_FIXTURE_READY");
            Thread.sleep(Math.min(600, Math.max(0, Integer.parseInt(hold))) * 1000L);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) { M01IntegrationTest.properties(props); }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BootstrapService bootstrap;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired M01IntegrationTest.TestClock clock;
    @Autowired AuthService auth;
    @Autowired TicketService service;
    @Autowired PlatformTransactionManager transactions;
    UUID adminId, employeeId, ownerId, otherId, strangerId;
    Browser admin, employee, owner, other, stranger;
    final String password = "TestPassword1234";
    final List<String> actions = List.of("claim", "assignments", "transfers", "suspensions", "resume",
        "resolutions", "close", "reopen", "priority-changes");
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach
    void setUp() throws Exception {
        clock.now = Instant.parse("2026-10-14T02:00:00Z");
        db.execute("TRUNCATE notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        adminId = bootstrap.initialize("M03 Admin", "m03-admin@example.com", password);
        employeeId = active("m03-employee@example.com", "EMPLOYEE");
        ownerId = active("m03-owner@example.com", "ENGINEER");
        otherId = active("m03-other@example.com", "ENGINEER");
        strangerId = active("m03-stranger@example.com", "EMPLOYEE");
        admin = signed("m03-admin@example.com"); employee = signed("m03-employee@example.com");
        owner = signed("m03-owner@example.com"); other = signed("m03-other@example.com"); stranger = signed("m03-stranger@example.com");
    }

    UUID active(String email, String role) {
        var user = new UserEntity(); user.id = UUID.randomUUID(); user.name = role;
        user.emailNormalized = email; user.role = role; user.status = "ACTIVE";
        user.passwordHash = passwords.encode(password); user.updatedAt = clock.instant(); users.insert(user);
        return user.id;
    }

    HttpResponse<String> call(Browser browser, String method, String path, Object body, String key, String version) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(20));
        if (!method.equals("GET")) {
            builder.header("Origin", "http://localhost:5173");
            if (browser.csrf() != null) builder.header("X-CSRF-Token", browser.csrf());
        }
        if (body != null) builder.header("Content-Type", "application/json");


        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return browser.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    Browser signed(String email) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .connectTimeout(Duration.ofSeconds(5)).build();
        Browser anon = new Browser(client, null);
        String csrf = data(call(anon, "GET", "/api/v1/auth/csrf", null, null, null)).path("csrfToken").asText();
        var login = call(new Browser(client, csrf), "POST", "/api/v1/auth/login", Map.of("email", email, "password", password), null, null);
        assertThat(login.statusCode()).as(login.body()).isEqualTo(200);
        return new Browser(client, data(login).path("csrfToken").asText());
    }

    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }

    UUID ticket(String state, UUID assignee) throws Exception {
        var response = call(employee, "POST", "/api/v1/tickets", Map.of("title", "M03 ticket", "description", "Workflow test",
            "businessTypeId", TicketTypeTestSupport.type(db, "IT"), "impactScope", "Test"), UUID.randomUUID().toString(), null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        UUID id = UUID.fromString(data(response).path("id").asText());
        db.update("UPDATE ticket SET status=?,assignee_id=? WHERE id=?", state, assignee, id);
        return id;
    }

    HttpResponse<String> action(Browser browser, UUID id, String action, Object body, long version) throws Exception {
        return call(browser, "POST", "/api/v1/tickets/" + id + "/" + action, body, UUID.randomUUID().toString(), "\"" + version + "\"");
    }

    void ok(HttpResponse<String> response, long version) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);


    }

    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var root = json.readTree(response.body());
        assertThat(root.path("error").path("code").asText()).isEqualTo(code);
        assertThat(root.path("error").path("message").asText()).isNotBlank();
        assertThat(root.path("error").path("fieldErrors").isArray()).isTrue();
        assertThat(root.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    String state(UUID id) { return db.queryForObject("SELECT status FROM ticket WHERE id=?", String.class, id); }
    long count(String table, UUID id) { return db.queryForObject("SELECT count(*) FROM " + table
        + (table.equals("audit_event") ? " WHERE object_id=?" : " WHERE resource_id=? AND operation LIKE 'POST /api/v1/tickets/%'"), Long.class, id); }

    @Test
    void nineActionsWorkWithoutResettingCycleExceptReopen() throws Exception {
        UUID id = ticket("NEW", null);
        Instant cycle = db.queryForObject("SELECT cycle_started_at FROM ticket WHERE id=?", java.sql.Timestamp.class, id).toInstant();
        assertThat(db.queryForObject("SELECT priority FROM ticket WHERE id=?", String.class, id)).isEqualTo("P3");
        ok(action(admin, id, "assignments", Map.of("assigneeId", ownerId), 0), 1);
        assertThat(state(id)).isEqualTo("NEW");
        ok(action(owner, id, "claim", null, 1), 2);
        ok(action(owner, id, "suspensions", Map.of("reason", " waiting "), 2), 3);
        assertThat(state(id)).isEqualTo("ON_HOLD");
        ok(action(owner, id, "resume", null, 3), 4);
        ok(action(owner, id, "transfers", Map.of("assigneeId", otherId), 4), 5);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        ok(action(other, id, "resolutions", Map.of("summary", " Fixed network "), 5), 6);
        ok(action(admin, id, "priority-changes", Map.of("priority", "P1", "reason", "Urgent"), 6), 7);
        assertThat(state(id)).isEqualTo("RESOLVED");
        assertThat(db.queryForObject("SELECT resolution_summary FROM ticket WHERE id=?", String.class, id)).isEqualTo("Fixed network");
        assertThat(db.queryForObject("SELECT cycle_started_at FROM ticket WHERE id=?", java.sql.Timestamp.class, id).toInstant()).isEqualTo(cycle);
        ok(action(employee, id, "close", null, 7), 8);
        assertThat(state(id)).isEqualTo("CLOSED");
        clock.advance(5);
        ok(action(employee, id, "reopen", Map.of("reason", "Reproduced"), 8), 9);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        var stored = db.queryForMap("SELECT assignee_id,closed_at,cycle_started_at,priority FROM ticket WHERE id=?", id);
        assertThat(stored.get("assignee_id")).isEqualTo(otherId);
        assertThat(stored.get("closed_at")).isNull();
        assertThat(((java.sql.Timestamp) stored.get("cycle_started_at")).toInstant()).isEqualTo(clock.instant());
        assertThat(stored.get("priority")).isEqualTo("P1");
        assertThat(count("audit_event", id)).isEqualTo(9);

        assertThat(db.queryForObject("SELECT reason FROM audit_event WHERE object_id=? AND action='TICKET_SUSPENSIONS'", String.class, id)).isEqualTo("waiting");
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE object_id=? AND object_type='TICKET'", Integer.class, id)).isEqualTo(9);
    }

    @Test
    void roleOwnershipVisibilityAndStatesAreEnforced() throws Exception {
        UUID id = ticket("IN_PROGRESS", ownerId);
        error(action(stranger, id, "reopen", Map.of("reason", "test"), 0), 404, "RESOURCE_NOT_FOUND");
        for (String name : List.of("suspensions", "resume", "resolutions", "transfers")) {
            Object body = switch (name) {
                case "transfers" -> Map.of("assigneeId", otherId);
                case "resolutions" -> Map.of("summary", "Fixed");
                case "suspensions" -> Map.of("reason", "Wait");
                default -> null;
            };
            error(action(other, id, name, body, 0), 403, "FORBIDDEN");
            error(action(admin, id, name.equals("transfers") ? "suspensions" : name,
                name.equals("transfers") ? Map.of("reason", "Wait") : body, 0), 403, "FORBIDDEN");
        }
        error(action(owner, id, "assignments", Map.of("assigneeId", otherId), 0), 403, "FORBIDDEN");
        error(action(owner, id, "priority-changes", Map.of("priority", "P1", "reason", "Urgent"), 0), 403, "FORBIDDEN");
        error(action(employee, id, "claim", null, 0), 403, "FORBIDDEN");
        error(action(owner, id, "resume", null, 0), 409, "INVALID_STATE");
        error(action(owner, id, "close", Map.of("reason", "Done"), 0), 409, "INVALID_STATE");
        error(action(owner, UUID.randomUUID(), "claim", null, 0), 404, "RESOURCE_NOT_FOUND");
        db.update("UPDATE ticket SET status='NEW' WHERE id=?", id);
        error(action(other, id, "claim", null, 0), 403, "FORBIDDEN");
        db.update("UPDATE ticket SET status='CLOSED' WHERE id=?", id);
        error(action(admin, id, "assignments", Map.of("assigneeId", otherId), 0), 409, "INVALID_STATE");
        assertThat(count("audit_event", id)).isZero();
    }



    @Test
    void reasonsSummaryPriorityAndAssigneeAreValidated() throws Exception {
        UUID id = ticket("IN_PROGRESS", ownerId);
        for (Object body : List.of(Map.of(), Map.of("reason", "  "), Map.of("reason", 12), Map.of("reason", true), Map.of("extra", "x")))
            error(action(owner, id, "suspensions", body, 0), 400, "VALIDATION_FAILED");
        for (Object body : List.of(Map.of(), Map.of("summary", "  "), Map.of("summary", 12), Map.of("reason", "x")))
            error(action(owner, id, "resolutions", body, 0), 400, "VALIDATION_FAILED");
        error(action(admin, id, "priority-changes", Map.of("priority", "P5", "reason", "x"), 0), 400, "VALIDATION_FAILED");
        error(action(admin, id, "priority-changes", Map.of("priority", "P1"), 0), 400, "VALIDATION_FAILED");
        for (UUID target : List.of(employeeId, UUID.randomUUID()))
            error(action(admin, id, "assignments", Map.of("assigneeId", target), 0), 400, "VALIDATION_FAILED");
        error(action(admin, id, "assignments", Map.of(), 0), 400, "VALIDATION_FAILED");
        db.update("UPDATE app_user SET status='DISABLED' WHERE id=?", otherId);
        error(action(owner, id, "transfers", Map.of("assigneeId", otherId), 0), 400, "VALIDATION_FAILED");
        error(action(owner, id, "transfers", Map.of("assigneeId", ownerId), 0), 400, "VALIDATION_FAILED");
        error(action(owner, id, "resume", Map.of(), 0), 400, "VALIDATION_FAILED");
        assertThat(state(id)).isEqualTo("IN_PROGRESS"); assertThat(count("audit_event", id)).isZero();
    }

    @Test
    void substituteCloseRequiresReasonAndReopenHasAnInclusiveSevenDayLimit() throws Exception {
        UUID id = ticket("RESOLVED", ownerId);
        error(action(other, id, "close", null, 0), 400, "VALIDATION_FAILED");
        ok(action(other, id, "close", Map.of("reason", "Confirmed with requester"), 0), 1);
        error(action(other, id, "reopen", Map.of("reason", "Again"), 1), 403, "FORBIDDEN");
        db.update("UPDATE ticket SET closed_at=? WHERE id=?", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(7))), id);
        ok(action(employee, id, "reopen", Map.of("reason", "Boundary"), 1), 2);
        db.update("UPDATE ticket SET status='CLOSED',closed_at=? WHERE id=?", java.sql.Timestamp.from(clock.instant().minus(Duration.ofDays(7)).minusSeconds(1)), id);
        error(action(admin, id, "reopen", Map.of("reason", "Too late"), 2), 409, "INVALID_STATE");
        db.update("UPDATE ticket SET status='RESOLVED' WHERE id=?", id);
        ok(action(owner, id, "reopen", Map.of("reason", "Resolved ticket"), 2), 3);
        db.update("UPDATE ticket SET status='RESOLVED' WHERE id=?", id);
        ok(action(admin, id, "close", Map.of("reason", "Admin confirmation"), 3), 4);
        ok(action(admin, id, "reopen", Map.of("reason", "Admin reopen"), 4), 5);
    }

    @Test
    void concurrentClaimsHaveOnlyOneWinner() throws Exception {
        UUID id = ticket("NEW", null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return action(owner, id, "claim", null, 0); });
            var two = pool.submit(() -> { start.await(); return action(other, id, "claim", null, 0); });
            start.countDown();
            var responses = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode)).containsExactlyInAnyOrder(200, 409);
            for (var response : responses) if (response.statusCode() == 409) error(response, 409, "INVALID_STATE");
        }
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        assertThat(db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?", UUID.class, id)).isIn(ownerId, otherId);
        assertThat(count("audit_event", id)).isEqualTo(1);
    }

    @Test
    void reopeningClearsDisabledOrDemotedAssigneeAndAllowsReassignment() throws Exception {
        UUID replacement = active("replacement@example.com", "ENGINEER");
        for (UUID original : List.of(ownerId, otherId)) {
            UUID id = ticket("CLOSED", original);
            db.update("UPDATE ticket SET closed_at=? WHERE id=?", java.sql.Timestamp.from(clock.instant()), id);
            if (original.equals(ownerId)) db.update("UPDATE app_user SET status='DISABLED' WHERE id=?", original);
            else db.update("UPDATE app_user SET role='EMPLOYEE' WHERE id=?", original);
            ok(action(employee, id, "reopen", Map.of("reason", "Issue reproduced"), 0), 1);
            assertThat(state(id)).isEqualTo("IN_PROGRESS");
            assertThat(db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?", UUID.class, id)).isNull();
            assertThat(db.queryForObject("SELECT (before_after->'after'->'assigneeId')='null'::jsonb FROM audit_event WHERE object_id=? AND action='TICKET_REOPEN'", Boolean.class, id)).isTrue();
            ok(action(admin, id, "assignments", Map.of("assigneeId", replacement), 1), 2);
            assertThat(db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?", UUID.class, id)).isEqualTo(replacement);
        }
    }

    @Test
    void concurrentAssignmentAndMemberDisableCannotLeaveDisabledAssignee() throws Exception {
        UUID id = ticket("NEW", null);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var assign = pool.submit(() -> { start.await(); return action(admin, id, "assignments", Map.of("assigneeId", otherId), 0); });
            var disable = pool.submit(() -> { start.await(); return call(admin, "POST", "/api/v1/users/" + otherId + "/disable",
                Map.of("reason", "Leaving company"), UUID.randomUUID().toString(), "\"0\""); });
            start.countDown();
            var a = assign.get(30, TimeUnit.SECONDS); var d = disable.get(30, TimeUnit.SECONDS);
            if (a.statusCode() == 200) {
                error(d, 409, "MEMBER_HAS_OPEN_TICKETS");
                assertThat(users.selectById(otherId).status).isEqualTo("ACTIVE");
            } else {
                error(a, 400, "VALIDATION_FAILED"); assertThat(d.statusCode()).isEqualTo(200);
                assertThat(db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?", UUID.class, id)).isNull();
            }
        }
    }

    @Test
    void transactionRollbackDoesNotLeaveStateAndBusinessHistoryChanges() throws Exception {
        UUID id = ticket("IN_PROGRESS", ownerId);
        var login = auth.login("m03-owner@example.com", password, null);
        var context = new AuthContext(login.session(), login.user(), login.rawToken());
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> new TransactionTemplate(transactions).execute(status -> {
            service.change(context, id, "suspensions", new TicketReasonRequest("Wait"), UUID.randomUUID());
            throw new IllegalStateException("Intentional test transaction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(state(id)).isEqualTo("IN_PROGRESS");
        assertThat(count("audit_event", id)).isZero();
        ok(call(owner, "POST", "/api/v1/tickets/" + id + "/suspensions", Map.of("reason", "Wait"), key, "\"0\""), 1);
    }

    @Test
    void v3TicketsUpgradeWithoutChangingTheirExistingData() {
        String schema = "m03_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var configuration = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        configuration.target("3").load().migrate();
        UUID user = UUID.randomUUID(), id = UUID.randomUUID();
        var created = java.sql.Timestamp.from(clock.instant().minusSeconds(100));
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'upgrade@example.com','Upgrade','EMPLOYEE','INVITED')", user);
        db.update("INSERT INTO " + schema + ".ticket(id,title,description,business_type,impact_scope,requester_id,version,created_at) VALUES (?,'Original','Description','IT','Test',?,7,?)", id, user, created);
        configuration.target("4").load().migrate();
        var row = db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id);
        assertThat(row.get("title")).isEqualTo("Original");
        assertThat(row.get("version")).isEqualTo(7L);
        assertThat(row.get("status")).isEqualTo("NEW");
        assertThat(row.get("priority")).isEqualTo("P3");
        assertThat(row.get("cycle_started_at")).isEqualTo(created);
        assertThat(row.get("closed_at")).isNull();
        assertThat(row.get("resolution_summary")).isNull();
    }

    @Test
    void authenticationCsrfAndHealthRemainEnforcedWithM05Reads() throws Exception {
        UUID id = ticket("NEW", null);
        var anon = new Browser(HttpClient.newHttpClient(), null);
        error(action(anon, id, "claim", null, 0), 401, "SESSION_EXPIRED");
        error(action(new Browser(owner.client(), null), id, "claim", null, 0), 403, "CSRF_INVALID");
        assertThat(call(owner, "GET", "/api/v1/tickets", null, null, null).statusCode()).isEqualTo(200);
        assertThat(call(owner, "GET", "/api/v1/tickets/" + id, null, null, null).statusCode()).isEqualTo(200);

        db.update("UPDATE app_user SET auth_version=auth_version+1 WHERE id=?", ownerId);
        error(action(owner, id, "claim", null, 0), 401, "SESSION_EXPIRED");
    }
}
