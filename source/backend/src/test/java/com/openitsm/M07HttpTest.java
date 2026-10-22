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
class M07HttpTest {
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
            "businessTypeId", TicketTypeTestSupport.type(db, "IT"), "impactScope", "Test"), UUID.randomUUID().toString(), null);
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
    JsonNode metrics(String query) throws Exception {
        var response = call(admin, "GET", "/api/v1/dashboard/metrics" + query, null, null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200); return data(response);
    }

    @Test void metricsUseCurrentResolutionSamplesAndExcludeRejectedFromLoad() throws Exception {
        UUID solved = ticket(), rejected = ticket(), opened = ticket();
        db.update("UPDATE ticket SET status='RESOLVED',assignee_id=?,resolved_at=created_at+interval '2 hours' WHERE id=?", engineerId, solved);
        db.update("UPDATE ticket SET status='REJECTED',assignee_id=?,rejection_reason='Outside scope',rejected_at=created_at WHERE id=?", engineerId, rejected);
        var value = metrics("");
        assertThat(value.path("totalCount").asInt()).isEqualTo(3);
        assertThat(value.path("countsByStatus").path("REJECTED").asInt()).isEqualTo(1);
        assertThat(value.path("unassignedCount").asInt()).isEqualTo(1);
        assertThat(value.path("assigneeLoads").get(0).path("openCount").asInt()).isEqualTo(1);
        assertThat(value.path("resolutionSampleCount").asInt()).isEqualTo(1);
        assertThat(value.path("averageResolutionSeconds").asDouble()).isEqualTo(7200);
        assertThat(detail(employee, opened).has("sla")).isFalse();
        db.update("UPDATE ticket SET status='IN_PROGRESS',resolved_at=NULL WHERE id=?", solved);
        assertThat(metrics("").path("resolutionSampleCount").asInt()).isZero();
        assertThat(metrics("").path("averageResolutionSeconds").isNull()).isTrue();
    }

    @Test void metricsUseOneCreationRangeAndHistoricalTypeIdentity() throws Exception {
        UUID first = ticket(), second = ticket();
        db.update("UPDATE ticket SET created_at='2026-10-18T15:59:59Z' WHERE id=?", first);
        db.update("UPDATE ticket SET created_at='2026-10-18T16:00:00Z' WHERE id=?", second);
        UUID type = TicketTypeTestSupport.type(db, "IT");
        db.update("UPDATE ticket_type SET name='Renamed',active=false WHERE id=?", type);
        var value = metrics("?createdFrom=2026-10-19&createdTo=2026-10-19");
        assertThat(value.path("totalCount").asInt()).isEqualTo(1);
        assertThat(value.path("countsByBusinessType").get(0).path("name").asText()).isEqualTo("Renamed");
        assertThat(value.path("countsByBusinessType").get(0).path("id").asText()).isEqualTo(type.toString());
        assertThat(value.path("unassignedCount").asInt()).isEqualTo(1);
        var empty = metrics("?createdTo=2026-10-17");
        assertThat(empty.path("totalCount").asInt()).isZero();
        assertThat(empty.path("averageResolutionSeconds").isNull()).isTrue();
    }

    @Test void metricsValidateRolesDatesAndHaveNoWriteSideEffects() throws Exception {
        for (Browser browser : List.of(employee, engineer)) error(call(browser, "GET", "/api/v1/dashboard/metrics", null, null, null), 403, "FORBIDDEN");
        for (String query : List.of("createdFrom=2026-02-30", "createdTo=bad", "createdFrom=2026-10-20&createdTo=2026-10-19"))
            error(call(admin, "GET", "/api/v1/dashboard/metrics?" + query, null, null, null), 400, "INVALID_QUERY");
        long audits = count("audit_event");
        metrics(""); metrics("");
        assertThat(count("audit_event")).isEqualTo(audits);

    }
}
