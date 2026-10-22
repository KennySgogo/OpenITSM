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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import static org.assertj.core.api.Assertions.*;

/** TCP HTTP against the actual Spring application and isolated PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(M01IntegrationTest.TimeConfig.class)
class ModuleExtensionsHttpTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.openitsm.config.AccountMailSchedulingConfig mailScheduler;

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
        db.execute("TRUNCATE notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
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


        if (method.equals("PATCH") && path.startsWith("/api/v1/knowledge-articles/") && body instanceof Map<?, ?> value && version != null) {
            var payload = new java.util.LinkedHashMap<String, Object>();
            value.forEach((k, v) -> payload.put(k.toString(), v));
            payload.put("version", Long.parseLong(version.replace("\"", ""))); body = payload;
        }
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
    String key() { return UUID.randomUUID().toString(); }
    String version(long value) { return "\"" + value + "\""; }
    void status(HttpResponse<String> response, int expected) { assertThat(response.statusCode()).as(response.body()).isEqualTo(expected); }
    UUID type() { return TicketTypeTestSupport.type(db, "IT"); }
    Map<String, Object> ticketBody(UUID type, UUID assignee) {
        var value = new java.util.LinkedHashMap<String, Object>();
        value.put("title", "Test ticket"); value.put("description", "Test description"); value.put("impactScope", "Team");
        value.put("businessTypeId", type); value.put("priority", "P1");
        if (assignee != null) value.put("assigneeId", assignee);
        return value;
    }
    UUID create(Browser browser, UUID type, UUID assignee) throws Exception {
        var result = call(browser, "POST", "/api/v1/tickets", ticketBody(type, assignee), key(), null);
        status(result, 201); return UUID.fromString(data(result).path("id").asText());
    }
    UUID adminId() { return users.lockEmail("m08-admin@example.com").id; }
    HttpResponse<String> action(Browser actor, UUID ticket, String name, Object body, long expected) throws Exception {
        return call(actor, "POST", "/api/v1/tickets/" + ticket + "/" + name, body, key(), version(expected));
    }

    @Test void profileOnlyEditsSelfUsesVersionAndReplaysWithoutRevokingSessions() throws Exception {
        var profile = call(employee, "GET", "/api/v1/profile", null, null, null);
        status(profile, 200); assertThat(data(profile).path("id").asText()).isEqualTo(employeeId.toString());
        String requestKey = key(); var body = Map.of("name", "New name", "skillsDescription", "VPN and networking");
        status(call(employee, "PATCH", "/api/v1/profile", body, requestKey, version(0)), 200);
        status(call(employee, "PATCH", "/api/v1/profile", Map.of("role", "ADMIN"), key(), version(1)), 400);
        status(call(employee, "PATCH", "/api/v1/profile", Map.of("name", " "), key(), version(1)), 400);
        status(call(employee, "PATCH", "/api/v1/profile", Map.of("skillsDescription", 4), key(), version(1)), 400);
        status(call(employee, "PATCH", "/api/v1/profile", Map.of(), key(), version(1)), 400);
        status(call(employee, "PATCH", "/api/v1/profile", body, key(), null), 200);
        status(call(employee, "GET", "/api/v1/auth/me", null, null, null), 200);
        assertThat(users.selectById(employeeId).authVersion).isZero();
        assertThat(users.selectById(employeeId).name).isEqualTo("New name");
        status(call(employee, "PATCH", "/api/v1/profile", Map.of("skillsDescription", ""), key(), version(1)), 200);
        assertThat(users.selectById(employeeId).skillsDescription).isEmpty();
    }

    @Test void passwordChangeChecksOldPasswordRevokesAllSessionsAndInvalidatesResetLinks() throws Exception {
        Browser second = signed("m08-employee@example.com");
        status(call(employee, "POST", "/api/v1/auth/password-changes", Map.of("oldPassword", "WrongPassword123", "password", "NewPassword1234", "confirmPassword", "NewPassword1234"), null, null), 400);
        status(call(second, "GET", "/api/v1/auth/me", null, null, null), 200);
        db.update("INSERT INTO account_token(token_hash,user_id,purpose,expires_at,generation) VALUES (?,?,?,?,1)", "a".repeat(64), employeeId, "RESET", java.sql.Timestamp.from(clock.instant().plusSeconds(1800)));
        var result = call(employee, "POST", "/api/v1/auth/password-changes", Map.of("oldPassword", password, "password", "NewPassword1234", "confirmPassword", "NewPassword1234"), null, null);
        status(result, 204); assertThat(result.headers().firstValue("Set-Cookie").orElseThrow()).contains("Max-Age=0");
        status(call(employee, "GET", "/api/v1/auth/me", null, null, null), 401);
        status(call(second, "GET", "/api/v1/auth/me", null, null, null), 401);
        assertThat(passwords.matches("NewPassword1234", users.selectById(employeeId).passwordHash)).isTrue();
        assertThat(db.queryForObject("SELECT count(*) FROM account_token WHERE user_id=? AND used_at IS NULL", Integer.class, employeeId)).isZero();
    }

    @Test void administratorCanBeAssignedAndRejectOwnTicketWithoutMailAndAllWritesBecomeReadonly() throws Exception {
        UUID adminId = adminId(), type = type();
        UUID ticket = create(employee, type, adminId);
        assertThat(db.queryForObject("SELECT status FROM ticket WHERE id=?", String.class, ticket)).isEqualTo("NEW");
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE ticket_id=?", Integer.class, ticket)).isEqualTo(1);
        status(action(admin, ticket, "claim", null, 0), 200);
        String requestKey = key(), path = "/api/v1/tickets/" + ticket + "/rejections";
        status(call(admin, "POST", path, Map.of("reason", "Outside scope"), requestKey, version(1)), 200);
        status(call(admin, "POST", path, Map.of("reason", "Outside scope"), requestKey, version(1)), 409);
        var detail = data(call(employee, "GET", "/api/v1/tickets/" + ticket, null, null, null));
        assertThat(detail.path("rejectionReason").asText()).isEqualTo("Outside scope");
        assertThat(detail.path("rejectedAt").isNull()).isFalse();
        assertThat(db.queryForObject("SELECT resolved_at FROM ticket WHERE id=?", java.sql.Timestamp.class, ticket)).isNull();
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE ticket_id=?", Integer.class, ticket)).isEqualTo(1);
        for (String operation : List.of("claim", "resume", "close", "reopen", "suspensions", "resolutions", "rejections", "priority-changes", "transfers", "assignments")) {
            Object body = switch (operation) {
                case "reopen", "suspensions", "rejections", "close" -> Map.of("reason", "Not allowed");
                case "resolutions" -> Map.of("summary", "Done");
                case "priority-changes" -> Map.of("priority", "P2", "reason", "Not allowed");
                case "assignments", "transfers" -> Map.of("assigneeId", engineerId);
                default -> null;
            };
            status(action(admin, ticket, operation, body, 2), 409);
        }
        status(action(employee, ticket, "replies", Map.of("content", "More info"), 2), 409);
        status(multipart(employee, "/api/v1/tickets/" + ticket + "/attachments", 32, 2, true), 409);
        UUID self = create(admin, type, adminId);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE ticket_id=?", Integer.class, self)).isZero();
    }

    @Test void adminMustTransferBeforeHandlingOtherOwnerAndInvalidTargetRollsBackCreation() throws Exception {
        UUID ticket = create(employee, type(), engineerId);
        status(action(admin, ticket, "claim", null, 0), 403);
        status(action(admin, ticket, "rejections", Map.of("reason", "Not mine"), 0), 403);
        status(action(admin, ticket, "transfers", Map.of("assigneeId", adminId()), 0), 200);
        status(action(admin, ticket, "claim", null, 1), 200);
        status(action(admin, ticket, "resolutions", Map.of("summary", "Fixed"), 2), 200);
        int before = db.queryForObject("SELECT count(*) FROM ticket", Integer.class);
        for (UUID target : List.of(employeeId, UUID.randomUUID())) status(call(employee, "POST", "/api/v1/tickets", ticketBody(type(), target), key(), null), 400);
        assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isEqualTo(before);
    }

    @Test void typeDescriptionsPreserveOmittedClearExplicitlyAndEnforceLimits() throws Exception {
        String endpoint = "/api/v1/ticket-types";
        String createKey = key();
        var body = Map.of("name", "Account", "description", "  Login and access requests  ");
        var created = call(engineer, "POST", endpoint, body, createKey, null);
        status(created, 201);
        UUID id = UUID.fromString(data(created).path("id").asText());
        String path = endpoint + "/" + id;
        assertThat(db.queryForObject("SELECT description FROM ticket_type WHERE id=?", String.class, id)).isEqualTo("Login and access requests");

        assertThat(data(call(employee, "GET", endpoint, null, null, null)).path("items").toString()).contains("Login and access requests");
        status(call(engineer, "PATCH", path, Map.of("name", "Renamed account"), key(), version(0)), 200);
        assertThat(db.queryForObject("SELECT description FROM ticket_type WHERE id=?", String.class, id)).isEqualTo("Login and access requests");
        status(call(employee, "PATCH", path, Map.of("name", "Denied", "description", "Denied"), key(), version(1)), 403);
        active("description-other@example.com", "ENGINEER");
        status(call(signed("description-other@example.com"), "PATCH", path, Map.of("name", "Denied", "description", "Denied"), key(), version(1)), 403);
        String clearKey = key();
        var clear = Map.of("name", "Renamed account", "description", " ");
        status(call(admin, "PATCH", path, clear, clearKey, version(1)), 200);

        assertThat(db.queryForObject("SELECT description FROM ticket_type WHERE id=?", String.class, id)).isNull();
        status(call(engineer, "PATCH", path, body, key(), version(1)), 200);
        status(call(admin, "PATCH", path, Map.of("name", "Account", "description", "😀".repeat(500)), key(), version(2)), 200);
        for (var invalid : List.of(json.readTree("{\"name\":\"Bad\",\"description\":null}"),
            json.readTree("{\"name\":\"Bad\",\"description\":42}"),
            json.valueToTree(Map.of("name", "Bad", "description", "😀".repeat(501))))) {
            status(call(admin, "PATCH", path, invalid, key(), version(3)), 400);
            status(call(admin, "POST", endpoint, invalid, key(), null), 400);
        }
        assertThat(db.queryForObject("SELECT version FROM ticket_type WHERE id=?", Long.class, id)).isEqualTo(4L);
    }



    @Test void typesEnforceCreatorPermissionsRenameHistoryAndPreserveSuccessfulReplayAfterRemoval() throws Exception {
        String typeKey = key(); var typeResponse = call(engineer, "POST", "/api/v1/ticket-types", Map.of("name", "Network"), typeKey, null);
        status(typeResponse, 201); UUID type = UUID.fromString(data(typeResponse).path("id").asText());

        String createKey = key(); var body = ticketBody(type, null);
        var first = call(employee, "POST", "/api/v1/tickets", body, createKey, null); status(first, 201);
        UUID ticket = UUID.fromString(data(first).path("id").asText());
        status(call(employee, "POST", "/api/v1/ticket-types", Map.of("name", "Illegal"), key(), null), 403);
        active("other-engineer@example.com", "ENGINEER"); Browser other = signed("other-engineer@example.com");
        status(call(other, "PATCH", "/api/v1/ticket-types/" + type, Map.of("name", "Illegal"), key(), version(0)), 403);
        status(call(engineer, "PATCH", "/api/v1/ticket-types/" + type, Map.of("name", "Renamed"), key(), version(0)), 200);
        assertThat(data(call(employee, "GET", "/api/v1/tickets/" + ticket, null, null, null)).path("businessType").asText()).isEqualTo("Renamed");
        status(call(admin, "DELETE", "/api/v1/ticket-types/" + type, null, key(), version(1)), 200);
        status(call(employee, "POST", "/api/v1/tickets", body, key(), null), 400);
        var historical = data(call(employee, "GET", "/api/v1/tickets/filter-options", null, null, null));
        assertThat(historical.has("assignees")).isFalse(); assertThat(historical.toString()).contains(type.toString(), "Renamed");
    }

    @Test void filtersUseNaturalDateBoundariesAndIntersectStaffAndLifecycleScopes() throws Exception {
        UUID first = create(employee, type(), engineerId), second = create(employee, type(), engineerId);
        db.update("UPDATE ticket SET created_at='2026-10-18T15:59:59Z',status='CLOSED' WHERE id=?", first);
        db.update("UPDATE ticket SET created_at='2026-10-18T16:00:00Z' WHERE id=?", second);
        var page = data(call(engineer, "GET", "/api/v1/tickets?view=ASSIGNED", null, null, null));
        assertThat(page.path("total").asInt()).isEqualTo(1);
        assertThat(data(call(engineer, "GET", "/api/v1/tickets?view=ASSIGNED&lifecycle=ALL", null, null, null)).path("total").asInt()).isEqualTo(2);
        String query = "/api/v1/tickets?view=ALL&createdFrom=2026-10-19&createdTo=2026-10-19&priority=P1&requesterId=" + employeeId + "&assigneeId=" + engineerId;
        assertThat(data(call(admin, "GET", query, null, null, null)).path("items").get(0).path("id").asText()).isEqualTo(second.toString());
        status(call(employee, "GET", "/api/v1/tickets?requesterId=" + employeeId, null, null, null), 403);
        status(call(admin, "GET", "/api/v1/tickets?createdFrom=2026-02-30", null, null, null), 400);
    }

    HttpResponse<String> multipart(Browser browser, String path, int size, long expected, boolean ticket) throws Exception {
        String boundary = "test-boundary-" + UUID.randomUUID();
        String prefix = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"sample.bin\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        String suffix = "\r\n" + (ticket ? "--" + boundary + "\r\nContent-Disposition: form-data; name=\"visibility\"\r\n\r\nPUBLIC\r\n" : "") + "--" + boundary + "--\r\n";
        var file = HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.InputStream() {
            int remaining = size;
            public int read() { return remaining-- > 0 ? 0 : -1; }
            public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int count = Math.min(remaining, length); java.util.Arrays.fill(bytes, offset, offset + count, (byte) 0); remaining -= count; return count;
            }
        });
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(90))
            .header("Origin", origin).header("X-CSRF-Token", browser.csrf())
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(prefix), file, HttpRequest.BodyPublishers.ofString(suffix))).build();
        return browser.client().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test void knowledgeAttachmentFollowsDraftAndPublicationPermissionsAndLogicalRemoval() throws Exception {
        var created = call(engineer, "POST", root, Map.of("title", "Guide", "content", "Text", "category", "IT"), key(), null);
        UUID article = UUID.fromString(data(created).path("resourceId").asText());
        status(multipart(admin, root + "/" + article + "/attachments", 12, 0, false), 403);
        status(multipart(engineer, root + "/" + article + "/attachments", 20 * 1024 * 1024, 0, false), 200);
        var listed = data(call(engineer, "GET", root + "/" + article + "/attachments", null, null, null));
        String attachment = listed.path("items").get(0).path("id").asText();
        status(call(employee, "GET", "/api/v1/knowledge-attachments/" + attachment, null, null, null), 404);
        status(call(admin, "POST", root + "/" + article + "/publish", null, key(), version(1)), 200);
        status(call(employee, "GET", "/api/v1/knowledge-attachments/" + attachment, null, null, null), 200);
        status(multipart(engineer, root + "/" + article + "/attachments", 12, 2, false), 409);
        status(call(admin, "POST", root + "/" + article + "/unpublish", null, key(), version(2)), 200);
        status(call(employee, "GET", "/api/v1/knowledge-attachments/" + attachment, null, null, null), 404);
        String removeKey = key(), path = root + "/" + article + "/attachments/" + attachment;
        status(call(engineer, "DELETE", path, null, removeKey, version(3)), 200);
        status(call(engineer, "DELETE", path, null, removeKey, version(3)), 200);
        status(call(engineer, "GET", "/api/v1/knowledge-attachments/" + attachment, null, null, null), 404);
        assertThat(db.queryForObject("SELECT removed_at IS NOT NULL FROM knowledge_attachment WHERE id=?", Boolean.class, UUID.fromString(attachment))).isTrue();
    }

    @Test void knowledgeSizeBoundaryDoesNotRelaxTicketLimit() throws Exception {
        var created = call(engineer, "POST", root, Map.of("title", "Guide", "content", "Text", "category", "IT"), key(), null);
        String article = data(created).path("resourceId").asText();
        status(multipart(engineer, root + "/" + article + "/attachments", 100 * 1024 * 1024, 0, false), 200);
        status(multipart(engineer, root + "/" + article + "/attachments", 100 * 1024 * 1024 + 1, 1, false), 413);
        status(multipart(engineer, root + "/" + article + "/attachments", 0, 1, false), 400);
        UUID ticket = create(employee, type(), null);
        status(multipart(employee, "/api/v1/tickets/" + ticket + "/attachments", 10 * 1024 * 1024 + 1, 0, true), 413);
    }

    @Test void processingRoleChangesRetainOwnershipAndDemotionWaitsForHandoff() throws Exception {
        UUID ticket = create(employee, type(), engineerId);
        status(call(admin, "PATCH", "/api/v1/users/" + engineerId, Map.of("role", "ADMIN"), key(), version(0)), 200);
        assertThat(db.queryForObject("SELECT assignee_id FROM ticket WHERE id=?", UUID.class, ticket)).isEqualTo(engineerId);
        status(call(engineer, "GET", "/api/v1/auth/me", null, null, null), 401);
        engineer = signed("m08-engineer@example.com");
        status(action(engineer, ticket, "claim", null, 0), 200);
        status(call(admin, "PATCH", "/api/v1/users/" + engineerId, Map.of("role", "EMPLOYEE"), key(), version(1)), 409);
        status(action(engineer, ticket, "rejections", Map.of("reason", "Cannot service"), 1), 200);
        status(call(admin, "PATCH", "/api/v1/users/" + engineerId, Map.of("role", "EMPLOYEE"), key(), version(1)), 200);
    }

    @Test void publicationAndUploadSerializeOnArticleState() throws Exception {
        var created = call(engineer, "POST", root, Map.of("title", "Guide", "content", "Text", "category", "IT"), key(), null);
        String article = data(created).path("resourceId").asText();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var upload = pool.submit(() -> { gate.await(); return multipart(engineer, root + "/" + article + "/attachments", 8192, 0, false).statusCode(); });
            var publish = pool.submit(() -> { gate.await(); return call(admin, "POST", root + "/" + article + "/publish", null, key(), version(0)).statusCode(); });
            gate.countDown();
            assertThat(publish.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(upload.get(30, TimeUnit.SECONDS)).isIn(200, 409);
        }
        assertThat(db.queryForObject("SELECT status FROM knowledge_article WHERE id=?", String.class, UUID.fromString(article))).isEqualTo("PUBLISHED");
    }

    @Test void removedTypeCannotWinTheRaceByCreatingAnInvalidTicket() throws Exception {
        UUID type = type(); var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var creation = pool.submit(() -> { gate.await(); return call(employee, "POST", "/api/v1/tickets", ticketBody(type, null), key(), null); });
            var removal = pool.submit(() -> { gate.await(); return call(admin, "DELETE", "/api/v1/ticket-types/" + type, null, key(), version(0)); });
            gate.countDown();
            status(removal.get(30, TimeUnit.SECONDS), 200);
            var result = creation.get(30, TimeUnit.SECONDS);
            assertThat(result.statusCode()).as(result.body()).isIn(201, 400);
            assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isEqualTo(result.statusCode() == 201 ? 1 : 0);
        }
    }



    @Test void v14UpgradeAddsNullableDescriptionWithoutChangingTypesOrTickets() {
        String schema = "type_description_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("14").load().migrate();
        UUID actor = UUID.randomUUID(), type = UUID.randomUUID(), ticket = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'old@example.com','Old','EMPLOYEE','INVITED')", actor);
        db.update("INSERT INTO " + schema + ".ticket_type(id,name,active,version) VALUES (?,'Legacy',false,3)", type);
        db.update("INSERT INTO " + schema + ".ticket(id,requester_id,title,description,business_type,business_type_id,impact_scope) VALUES (?,?,'Old','Original','Legacy',?,'Team')", ticket, actor, type);
        var beforeTypes = db.queryForList("SELECT id,name,active,version FROM " + schema + ".ticket_type");
        var beforeTickets = db.queryForList("SELECT id,business_type_id,description FROM " + schema + ".ticket");
        assertThat(config.target("15").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForList("SELECT id,name,active,version FROM " + schema + ".ticket_type")).isEqualTo(beforeTypes);
        assertThat(db.queryForList("SELECT id,business_type_id,description FROM " + schema + ".ticket")).isEqualTo(beforeTickets);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket_type WHERE description IS NULL", Integer.class)).isEqualTo(1);
        assertThat(config.target("15").load().migrate().migrationsExecuted).isZero();
    }

    @Test void v9UpgradeMapsExactLegacyTypesWithoutChangingOriginalFields() {
        String schema = "extensions_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("9").load().migrate(); UUID actor = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'old@example.com','Old','EMPLOYEE','INVITED')", actor);
        for (String name : List.of("Network", "Network", "network")) db.update("INSERT INTO " + schema + ".ticket(id,requester_id,title,description,business_type,impact_scope) VALUES (?,?,'Old','Original',?,'Team')", UUID.randomUUID(), actor, name);
        var before = db.queryForList("SELECT id,business_type,title,description FROM " + schema + ".ticket ORDER BY id");
        assertThat(config.target("13").load().migrate().migrationsExecuted).isEqualTo(4);
        assertThat(db.queryForList("SELECT id,business_type,title,description FROM " + schema + ".ticket ORDER BY id")).isEqualTo(before);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket_type", Integer.class)).isEqualTo(2);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket t JOIN " + schema + ".ticket_type bt ON bt.id=t.business_type_id AND bt.name=t.business_type", Integer.class)).isEqualTo(3);
        assertThat(config.target("13").load().migrate().migrationsExecuted).isZero();
    }

}
