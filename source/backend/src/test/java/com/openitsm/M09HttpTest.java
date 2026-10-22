package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.BootstrapService;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(M01IntegrationTest.TimeConfig.class)
class M09HttpTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.openitsm.config.AccountMailSchedulingConfig mailScheduler;

    static final AtomicInteger CALLS = new AtomicInteger();
    static volatile String response = "{}";
    static volatile String requestBody = "";
    static volatile CountDownLatch entered, release;
    static final HttpServer PYTHON;
    static {
        try {
            PYTHON = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            PYTHON.createContext("/internal/v1/", exchange -> {
                CALLS.incrementAndGet();
                requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (entered != null) entered.countDown();
                try { if (release != null) release.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                byte[] body = response.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            });
            PYTHON.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> PYTHON.stop(0)));
        } catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        M01IntegrationTest.properties(props);
        props.add("openitsm.ai.service-url", () -> "http://127.0.0.1:" + PYTHON.getAddress().getPort());
        props.add("openitsm.model.base-url", () -> "https://bootstrap.example/v1");
        props.add("openitsm.model.name", () -> "bootstrap-model");
        props.add("openitsm.model.api-key", () -> "bootstrap-model-test-secret");
    }
    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BootstrapService bootstrap;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    @Autowired M01IntegrationTest.TestClock clock;
    Browser admin, employee, owner, other, stranger;
    UUID employeeId, ownerId;
    UUID oldType, newType;
    final String password = "TestPassword1234";
    String origin = "http://localhost:5173";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach void setUp() throws Exception {
        clock.now = Instant.parse("2026-10-21T00:00:00Z");
        db.execute("TRUNCATE team_settings,ai_connection_status,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        bootstrap.initialize("M09 Admin", "m09-admin@example.com", password);
        employeeId = active("m09-employee@example.com", "EMPLOYEE");
        ownerId = active("m09-owner@example.com", "ENGINEER");
        active("m09-other@example.com", "ENGINEER");
        active("m09-stranger@example.com", "EMPLOYEE");
        admin = signed("m09-admin@example.com"); employee = signed("m09-employee@example.com");
        owner = signed("m09-owner@example.com"); other = signed("m09-other@example.com"); stranger = signed("m09-stranger@example.com");
        oldType = TicketTypeTestSupport.type(db, "M09 Old"); newType = TicketTypeTestSupport.type(db, "M09 New");
        CALLS.set(0); response = "{}"; requestBody = ""; entered = null; release = null;
    }
    UUID active(String email, String role) {
        var user = new UserEntity(); user.id = UUID.randomUUID(); user.emailNormalized = email;
        user.name = role; user.role = role; user.status = "ACTIVE"; user.passwordHash = passwords.encode(password);
        user.updatedAt = clock.instant(); users.insert(user); return user.id;
    }
    HttpResponse<String> call(Browser browser, String method, String path, Object body, String key, String version) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(25));
        if (!method.equals("GET")) {
            request.header("Origin", origin);
            if (browser.csrf != null) request.header("X-CSRF-Token", browser.csrf);
        }


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
    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).path("error").path("code").asText()).isEqualTo(code);
    }
    UUID ticket(String state) throws Exception {
        var created = call(employee, "POST", "/api/v1/tickets", Map.of("title", "Public network issue", "description", "Network unavailable",
            "businessTypeId", oldType, "impactScope", "Team"), UUID.randomUUID().toString(), null);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        UUID id = UUID.fromString(data(created).path("id").asText());
        db.update("UPDATE ticket SET status=?,assignee_id=? WHERE id=?", state, ownerId, id);
        return id;
    }
    String path(UUID id) { return "/api/v1/tickets/" + id; }
    HttpResponse<String> confirm(Browser browser, UUID id, Object body, String key, long version) throws Exception {
        return call(browser, "POST", path(id) + "/classifications", body, key, "\"" + version + "\"");
    }
    HttpResponse<String> suggest(Browser browser, UUID id) throws Exception {
        return call(browser, "POST", path(id) + "/ai/classification-suggestions", null, null, null);
    }
    void enableAi() throws Exception {
        var result = call(admin, "PATCH", "/api/v1/settings", Map.of("aiEnabled", true), UUID.randomUUID().toString(), "\"0\"");
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    }

    @Test void m10AllRolesBeforeCreateAndStaffAfterCreateUseOnlyAuthorizedAggregates() throws Exception {
        enableAi();
        UUID historical = ticket("CLOSED");
        db.update("UPDATE app_user SET skills_description='Network specialist' WHERE id=?", ownerId);
        response = json.writeValueAsString(Map.of("items", List.of(Map.of("assigneeId", ownerId, "reason", "具有相关技能"))));
        var body = Map.of("title", "Network issue", "description", "Public description", "businessTypeId", oldType, "impactScope", "Team");
        long count = db.queryForObject("SELECT count(*) FROM ticket", Long.class);
        long mail = db.queryForObject("SELECT count(*) FROM notification_task", Long.class);
        for (var browser : List.of(admin, owner, employee)) {
            var result = call(browser, "POST", "/api/v1/ai/assignee-recommendations", body, null, null);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            assertThat(data(result).path("items").get(0).path("assigneeId").asText()).isEqualTo(ownerId.toString());
        }
        var materials = json.readTree(requestBody);
        assertThat(materials.path("ticket").path("description").asText()).isEqualTo("Public description");
        assertThat(materials.path("candidates").toString()).doesNotContain("email", "password", "Network unavailable");
        var ownerMaterial = java.util.stream.StreamSupport.stream(materials.path("candidates").spliterator(), false)
            .filter(node -> node.path("id").asText().equals(ownerId.toString())).findFirst().orElseThrow();
        assertThat(ownerMaterial.path("resolvedCount").asLong()).isEqualTo(1);
        assertThat(ownerMaterial.path("skillsDescription").asText()).isEqualTo("Network specialist");
        assertThat(db.queryForObject("SELECT count(*) FROM ticket", Long.class)).isEqualTo(count);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task", Long.class)).isEqualTo(mail);
        error(call(employee, "POST", path(historical)+"/ai/assignee-recommendations", null, null, null), 403, "FORBIDDEN");
        assertThat(call(other, "POST", path(historical)+"/ai/assignee-recommendations", null, null, null).statusCode()).isEqualTo(200);
        error(call(other, "POST", path(historical)+"/assignments", Map.of("assigneeId", ownerId), null, null), 403, "FORBIDDEN");
    }

    @Test void m10RejectsInvalidInputAndForeignCandidatesWithoutChangingBusinessData() throws Exception {
        enableAi();
        var body = new HashMap<String,Object>(Map.of("title", "Issue", "description", "Description", "businessTypeId", oldType, "impactScope", "Team"));
        body.put("candidates", List.of(ownerId));
        error(call(employee, "POST", "/api/v1/ai/assignee-recommendations", body, null, null), 400, "VALIDATION_FAILED");
        assertThat(CALLS.get()).isZero();
        body.remove("candidates");
        response = json.writeValueAsString(Map.of("items", List.of(Map.of("assigneeId", employeeId, "reason", "invalid"))));
        error(call(employee, "POST", "/api/v1/ai/assignee-recommendations", body, null, null), 503, "AI_INVALID_RESPONSE");
        db.update("UPDATE team_settings SET ai_enabled=false");
        error(call(employee, "POST", "/api/v1/ai/assignee-recommendations", body, null, null), 503, "AI_DISABLED");
    }

    @Test void m10FiltersCandidateDisabledDuringModelWait() throws Exception {
        enableAi();
        response = json.writeValueAsString(Map.of("items", List.of(Map.of("assigneeId", ownerId, "reason", "skill"))));
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        var future = CompletableFuture.supplyAsync(() -> {
            try { return call(employee, "POST", "/api/v1/ai/assignee-recommendations", Map.of("title", "Issue", "description", "Description", "businessTypeId", oldType, "impactScope", "Team"), null, null); }
            catch (Exception ex) { throw new RuntimeException(ex); }
        });
        try {
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE app_user SET status='DISABLED' WHERE id=?", ownerId);
        } finally { release.countDown(); }
        var result = future.get(15, TimeUnit.SECONDS);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(data(result).path("items").size()).isZero();
    }

    @Test void manualConfirmationChecksRolesStateReplayAndNoPriorityOrMail() throws Exception {
        UUID id = ticket("NEW");
        String key = UUID.randomUUID().toString();
        error(confirm(stranger, id, Map.of("businessTypeId", newType), UUID.randomUUID().toString(), 0), 404, "RESOURCE_NOT_FOUND");
        error(confirm(other, id, Map.of("businessTypeId", newType), UUID.randomUUID().toString(), 0), 403, "FORBIDDEN");
        error(confirm(employee, id, Map.of("businessTypeId", newType, "priority", "P1"), UUID.randomUUID().toString(), 0), 400, "VALIDATION_FAILED");
        var first = confirm(employee, id, Map.of("businessTypeId", newType), key, 0);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(200);



        assertThat(confirm(employee, id, Map.of("businessTypeId", newType), null, 0).statusCode()).isEqualTo(200);
        var stored = db.queryForMap("SELECT business_type_id,business_type,priority,status,assignee_id,version FROM ticket WHERE id=?", id);
        assertThat(stored.get("business_type_id")).isEqualTo(newType);
        assertThat(stored.get("priority")).isEqualTo("P3");
        assertThat(stored.get("version")).isEqualTo(1L);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE ticket_id=?", Integer.class, id)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE object_id=? AND action='TICKET_CLASSIFICATIONS'", Integer.class, id)).isEqualTo(1);
        var history = data(call(employee, "GET", path(id) + "/operations", null, null, null));
        assertThat(history.path("total").asInt()).isEqualTo(1);
        assertThat(history.path("items").get(0).path("action").asText()).isEqualTo("TICKET_CLASSIFICATIONS");
        db.update("UPDATE ticket SET status='IN_PROGRESS' WHERE id=?", id);
        error(confirm(employee, id, Map.of("businessTypeId", oldType), UUID.randomUUID().toString(), 1), 403, "FORBIDDEN");
        assertThat(confirm(owner, id, Map.of("businessTypeId", oldType), UUID.randomUUID().toString(), 1).statusCode()).isEqualTo(200);
        db.update("UPDATE ticket SET status='RESOLVED' WHERE id=?", id);
        error(confirm(admin, id, Map.of("businessTypeId", newType), UUID.randomUUID().toString(), 2), 409, "INVALID_STATE");
        assertThat(CALLS).hasValue(0);
    }

    @Test void inactiveTypeFailsAndAiFailureStillAllowsManualSelection() throws Exception {
        UUID id = ticket("NEW");
        error(suggest(employee, id), 503, "AI_DISABLED");
        db.update("UPDATE ticket_type SET active=false WHERE id=?", newType);
        error(confirm(employee, id, Map.of("businessTypeId", newType), UUID.randomUUID().toString(), 0), 400, "VALIDATION_FAILED");
        assertThat(confirm(employee, id, Map.of("businessTypeId", oldType), UUID.randomUUID().toString(), 0).statusCode()).isEqualTo(200);
        assertThat(CALLS).hasValue(0);
    }

    @Test void suggestionsUsePublicFieldsAndRecheckTypeValidity() throws Exception {
        UUID id = ticket("NEW"); enableAi();
        error(suggest(stranger, id), 404, "RESOURCE_NOT_FOUND");
        error(suggest(other, id), 403, "FORBIDDEN");
        response = json.writeValueAsString(Map.of("businessTypeId", newType, "reason", "Network issue", "suggestedPriority", "P2"));
        var result = suggest(employee, id);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(data(result).path("businessTypeId").asText()).isEqualTo(newType.toString());
        assertThat(requestBody).contains("Public network issue", "Network unavailable", "Team", newType.toString());
        assertThat(requestBody).doesNotContain("internalNote", "attachment", "resolutionSummary");
        assertThat(db.queryForObject("SELECT business_type_id FROM ticket WHERE id=?", UUID.class, id)).isEqualTo(oldType);
        assertThat(db.queryForObject("SELECT status FROM ai_connection_status WHERE kind='CALL'", String.class)).isEqualTo("SUCCESS");
        response = json.writeValueAsString(Map.of("businessTypeId", UUID.randomUUID(), "reason", "bad", "suggestedPriority", "P1"));
        error(suggest(employee, id), 503, "AI_INVALID_RESPONSE");
        assertThat(db.queryForObject("SELECT status FROM ai_connection_status WHERE kind='CALL'", String.class)).isEqualTo("AI_INVALID_RESPONSE");
        db.update("UPDATE ticket SET status='CLOSED' WHERE id=?", id);
        error(suggest(admin, id), 409, "INVALID_STATE");
        assertThat(CALLS).hasValue(2);
    }

    @Test void staleStateOrConfigDuringModelCallCannotReturnSuggestion() throws Exception {
        UUID id = ticket("NEW"); enableAi();
        response = json.writeValueAsString(Map.of("businessTypeId", newType, "reason", "Network issue", "suggestedPriority", "P2"));
        entered = new CountDownLatch(1); release = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var pending = pool.submit(() -> suggest(employee, id));
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            db.update("UPDATE ticket SET status='CLOSED' WHERE id=?", id);
            release.countDown();
            error(pending.get(), 409, "INVALID_STATE");
        } finally { release.countDown(); entered = null; release = null; }
    }

    @Test void missingFailedOrStaleConnectionTestsDoNotBlockSuggestions() throws Exception {
        UUID id = ticket("NEW"); enableAi();
        response = json.writeValueAsString(Map.of("businessTypeId", newType, "reason", "Network", "suggestedPriority", "P2"));
        assertThat(suggest(employee, id).statusCode()).isEqualTo(200);
        assertThat(CALLS).hasValue(1);
        assertThat(json.readTree(requestBody).has("outputMode")).isFalse();
        db.update("INSERT INTO ai_connection_status(kind,config_version,status,occurred_at,attempt_id) VALUES ('TEST',0,'DEPENDENCY_UNAVAILABLE',?,?)", java.sql.Timestamp.from(clock.instant()), UUID.randomUUID());
        assertThat(suggest(employee, id).statusCode()).isEqualTo(200);
        db.update("UPDATE ai_connection_status SET status='SUCCESS' WHERE kind='TEST'");
        assertThat(suggest(employee, id).statusCode()).isEqualTo(200);
        assertThat(CALLS).hasValue(3);
        assertThat(confirm(employee, id, Map.of("businessTypeId", newType), UUID.randomUUID().toString(), 0).statusCode()).isEqualTo(200);
        db.update("UPDATE ticket_type SET active=false");
        var empty = suggest(employee, id);
        assertThat(empty.statusCode()).as(empty.body()).isEqualTo(200);
        assertThat(data(empty).path("businessTypeId").isNull()).isTrue();
        assertThat(CALLS).hasValue(3);
    }

    @Test void inFlightTypePermissionConfigAndSwitchChangesAreRechecked() throws Exception {
        for (String change : List.of("TYPE", "PERMISSION", "CONFIG", "SWITCH")) {
            setUp();
            UUID id = ticket("NEW"); enableAi();
            response = json.writeValueAsString(Map.of("businessTypeId", newType, "reason", "Network issue", "suggestedPriority", "P2"));
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            try (var pool = Executors.newSingleThreadExecutor()) {
                var pending = pool.submit(() -> suggest(employee, id));
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                switch (change) {
                    case "TYPE" -> db.update("UPDATE ticket_type SET active=false WHERE id=?", newType);
                    case "PERMISSION" -> db.update("UPDATE app_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?", employeeId);
                    case "CONFIG" -> db.update("UPDATE team_settings SET version=version+1");
                    case "SWITCH" -> db.update("UPDATE team_settings SET ai_enabled=false,version=version+1");
                }
                release.countDown();
                var result = pending.get();
                if (change.equals("PERMISSION")) error(result, 401, "SESSION_EXPIRED");
                else if (change.equals("TYPE")) error(result, 503, "AI_INVALID_RESPONSE");
                else if (change.equals("SWITCH")) error(result, 503, "AI_DISABLED");
                else error(result, 503, "DEPENDENCY_UNAVAILABLE");
            } finally { release.countDown(); entered = null; release = null; }
        }
    }

    @Test void preCreationSuggestionsAllowAllRolesWithoutCreatingBusinessRows() throws Exception {
        enableAi();
        response = json.writeValueAsString(Map.of("businessTypeId", newType, "reason", "Network", "suggestedPriority", "P2"));
        var counts = new HashMap<String, Integer>();
        for (String table : List.of("ticket", "audit_event", "notification_task", "ticket_attachment"))
            counts.put(table, db.queryForObject("SELECT count(*) FROM " + table, Integer.class));
        for (Browser browser : List.of(employee, owner, admin)) {
            var result = call(browser, "POST", "/api/v1/ai/classification-suggestions", Map.of("title", "  VPN issue  ", "description", " Network unavailable "), null, null);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            assertThat(data(result).path("businessTypeId").asText()).isEqualTo(newType.toString());
            assertThat(data(result).path("suggestedPriority").asText()).isEqualTo("P2");
            var sent = json.readTree(requestBody);
            assertThat(sent.path("ticket").path("title").asText()).isEqualTo("VPN issue");
            assertThat(sent.path("ticket").path("impactScope").asText()).isEmpty();
            assertThat(sent.path("businessTypes").toString()).contains(newType.toString());
            assertThat(sent.path("ticket").has("businessTypeId")).isFalse();
        }
        assertThat(CALLS).hasValue(3);
        for (var count : counts.entrySet())
            assertThat(db.queryForObject("SELECT count(*) FROM " + count.getKey(), Integer.class)).isEqualTo(count.getValue());
    }

    @Test void preCreationValidatesFieldsSessionAndCsrfBeforeModel() throws Exception {
        enableAi();
        for (Object body : List.of(Map.of(), Map.of("title", " ", "description", "Text"),
            Map.of("title", 1, "description", "Text"), Map.of("title", "VPN", "description", true),
            Map.of("title", "VPN", "description", "Text", "impactScope", 1),
            Map.of("title", "VPN", "description", "Text", "businessTypeId", newType),
            Map.of("title", "VPN", "description", "Text", "businessTypes", List.of()),
            Map.of("title", "x".repeat(1001), "description", "Text"),
            Map.of("title", "VPN", "description", "x".repeat(60001)),
            Map.of("title", "VPN", "description", "Text", "impactScope", "x".repeat(4001))))
            error(call(employee, "POST", "/api/v1/ai/classification-suggestions", body, null, null), 400, "VALIDATION_FAILED");
        var valid = Map.of("title", "VPN", "description", "Text");
        error(call(new Browser(HttpClient.newHttpClient(), null), "POST", "/api/v1/ai/classification-suggestions", valid, null, null), 401, "SESSION_EXPIRED");
        error(call(new Browser(employee.client(), null), "POST", "/api/v1/ai/classification-suggestions", valid, null, null), 403, "CSRF_INVALID");
        assertThat(CALLS).hasValue(0);
    }

    @Test void preCreationNoCandidatesAndDisabledAiPreserveManualCreation() throws Exception {
        var body = Map.of("title", "VPN", "description", "Network unavailable", "impactScope", "");
        error(call(employee, "POST", "/api/v1/ai/classification-suggestions", body, null, null), 503, "AI_DISABLED");
        assertThat(ticket("NEW")).isNotNull();
        enableAi();
        db.update("UPDATE ticket_type SET active=false");
        var result = call(employee, "POST", "/api/v1/ai/classification-suggestions", body, null, null);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
        assertThat(data(result).path("businessTypeId").isNull()).isTrue();
        assertThat(CALLS).hasValue(0);
    }

    @Test void preCreationRechecksUserConfigSwitchAndTypeAfterModelWait() throws Exception {
        for (String change : List.of("TYPE", "PERMISSION", "CONFIG", "SWITCH")) {
            setUp(); enableAi();
            response = json.writeValueAsString(Map.of("businessTypeId", newType, "reason", "Network", "suggestedPriority", "P2"));
            entered = new CountDownLatch(1); release = new CountDownLatch(1);
            try {
                var result = CompletableFuture.supplyAsync(() -> {
                    try { return call(employee, "POST", "/api/v1/ai/classification-suggestions", Map.of("title", "VPN", "description", "Network"), null, null); }
                    catch (Exception ex) { throw new CompletionException(ex); }
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                switch (change) {
                    case "TYPE" -> db.update("UPDATE ticket_type SET active=false WHERE id=?", newType);
                    case "PERMISSION" -> db.update("UPDATE app_user SET status='DISABLED',auth_version=auth_version+1 WHERE id=?", employeeId);
                    case "CONFIG" -> db.update("UPDATE team_settings SET version=version+1");
                    case "SWITCH" -> db.update("UPDATE team_settings SET ai_enabled=false,version=version+1");
                }
                release.countDown();
                var response = result.get(8, TimeUnit.SECONDS);
                if (change.equals("PERMISSION")) error(response, 401, "SESSION_EXPIRED");
                else if (change.equals("TYPE")) error(response, 503, "AI_INVALID_RESPONSE");
                else if (change.equals("SWITCH")) error(response, 503, "AI_DISABLED");
                else error(response, 503, "DEPENDENCY_UNAVAILABLE");
                assertThat(db.queryForObject("SELECT count(*) FROM ticket", Integer.class)).isZero();
            } finally { release.countDown(); entered = null; release = null; }
        }
    }

    @Test void packagedApplicationAndRealPythonWhenRequested() throws Exception {
        if ("true".equals(System.getenv("OPENITSM_M09_PACKAGED_VERIFY"))) M09PackagedSupport.verify(this);
    }
}
