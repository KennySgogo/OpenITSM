package com.openitsm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.BootstrapService;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class M05HttpTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.openitsm.config.AccountMailSchedulingConfig mailScheduler;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) { M01IntegrationTest.properties(props); }
    @AfterAll
    static void holdForBrowserVerification() throws InterruptedException {
        String hold = System.getenv("OPENITSM_M05_HOLD_SECONDS");
        if (hold != null) {
            System.out.println("M05_FIXTURE_READY");
            Thread.sleep(Math.min(300, Math.max(0, Integer.parseInt(hold))) * 1000L);
        }
    }

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired BootstrapService bootstrap;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder passwords;
    Browser admin, employee, engineer, stranger;
    List<String> mine;
    String foreign, engineerTicket;
    final String password = "TestPassword1234";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach
    void setUp() throws Exception {
        db.execute("TRUNCATE notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        bootstrap.initialize("M05 Admin", "m05-admin@example.com", password);
        active("m05-employee@example.com", "EMPLOYEE", "M05 员工");
        UUID engineerId = active("m05-engineer@example.com", "ENGINEER", "M05 工程师");
        active("m05-stranger@example.com", "EMPLOYEE", "Other employee");
        admin = signed("m05-admin@example.com"); employee = signed("m05-employee@example.com");
        engineer = signed("m05-engineer@example.com"); stranger = signed("m05-stranger@example.com");
        mine = new ArrayList<>();
        for (int i = 0; i < 23; i++) mine.add(create(employee, i == 0 ? "Network outage" : i == 1 ? "100%_path\\" : "M05 item " + i,
            i == 1 ? "VPN access denied <script>text only</script>" : "Office device issue", i == 0 ? "Network" : "General"));
        foreign = create(stranger, "Foreign secret marker", "Restricted requester", "Network");
        engineerTicket = create(engineer, "Engineer own ticket", "Own issue", "General");
        db.update("UPDATE ticket SET assignee_id=?,status='IN_PROGRESS',version=3 WHERE id=?", engineerId, UUID.fromString(mine.getFirst()));
        db.update("UPDATE ticket SET status='RESOLVED',resolution_summary='Public network solution' WHERE id=?", UUID.fromString(mine.get(1)));
        db.execute("UPDATE ticket SET created_at=TIMESTAMPTZ '2026-10-16 00:00:00+00'");
    }

    UUID active(String email, String role, String name) {
        var user = new UserEntity(); user.id = UUID.randomUUID(); user.emailNormalized = email; user.name = name;
        user.role = role; user.status = "ACTIVE"; user.passwordHash = passwords.encode(password); user.updatedAt = Instant.now();
        users.insert(user); return user.id;
    }
    HttpResponse<String> call(Browser browser, String method, String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(15));
        if (!method.equals("GET")) request.header("Origin", "http://localhost:5173").header("X-CSRF-Token", browser.csrf());
        if (body != null) request.header("Content-Type", "application/json");
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return browser.client().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
    JsonNode data(HttpResponse<String> response) throws Exception { return json.readTree(response.body()).path("data"); }
    Browser signed(String email) throws Exception {
        var client = HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL)).build();
        var anon = new Browser(client, null);
        String csrf = data(call(anon, "GET", "/api/v1/auth/csrf", null)).path("csrfToken").asText();
        var result = call(new Browser(client, csrf), "POST", "/api/v1/auth/login", Map.of("email", email, "password", password));
        assertThat(result.statusCode()).isEqualTo(200);
        return new Browser(client, data(result).path("csrfToken").asText());
    }
    String create(Browser browser, String title, String description, String businessType) throws Exception {
        var result = call(browser, "POST", "/api/v1/tickets", Map.of("title", title, "description", description, "businessTypeId", TicketTypeTestSupport.type(db, businessType), "impactScope", "Test scope"));
        assertThat(result.statusCode()).isEqualTo(201); return data(result).path("id").asText();
    }
    JsonNode page(Browser browser, String query) throws Exception {
        var result = call(browser, "GET", "/api/v1/tickets" + query, null);
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200); return data(result);
    }
    List<String> ids(JsonNode page) {
        var ids = new ArrayList<String>(); page.path("items").forEach(item -> ids.add(item.path("id").asText())); return ids;
    }
    String query(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    void error(HttpResponse<String> response, int status, String code) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        var root = json.readTree(response.body());
        assertThat(root.path("error").path("code").asText()).isEqualTo(code);
        assertThat(root.path("error").path("fieldErrors").isArray()).isTrue();
        assertThat(root.path("error").path("retryable").asBoolean()).isFalse();
        assertThat(root.path("requestId").asText()).isEqualTo(response.headers().firstValue("X-Request-ID").orElseThrow());
    }

    @Test
    void employeeVisibilityIsEnforcedInBothListAndDetail() throws Exception {
        assertThat(page(employee, "").path("total").asInt()).isEqualTo(23);
        for (String view : List.of("ASSIGNED", "UNASSIGNED", "ALL"))
            error(call(employee, "GET", "/api/v1/tickets?view=" + view, null), 403, "FORBIDDEN");
        error(call(employee, "GET", "/api/v1/tickets/" + foreign, null), 404, "RESOURCE_NOT_FOUND");
        error(call(employee, "GET", "/api/v1/tickets/" + UUID.randomUUID(), null), 404, "RESOURCE_NOT_FOUND");
        assertThat(call(engineer, "GET", "/api/v1/tickets/" + foreign, null).statusCode()).isEqualTo(200);
        assertThat(call(admin, "GET", "/api/v1/tickets/" + foreign, null).statusCode()).isEqualTo(200);
        assertThat(page(employee, "?q=" + query("Foreign secret marker")).path("total").asInt()).isZero();
    }

    @Test
    void allViewsAndStablePaginationUseActualPersistedData() throws Exception {
        var first = page(employee, "?page=1");
        var second = page(employee, "?page=2");
        var expected = mine.stream().sorted(Comparator.reverseOrder()).toList();
        assertThat(ids(first)).containsExactlyElementsOf(expected.subList(0, 20));
        assertThat(ids(second)).containsExactlyElementsOf(expected.subList(20, 23));
        assertThat(first.path("pageSize").asInt()).isEqualTo(20);
        assertThat(second.path("page").asInt()).isEqualTo(2);
        assertThat(page(admin, "?view=MINE").path("total").asInt()).isZero();
        assertThat(page(admin, "?view=ALL").path("total").asInt()).isEqualTo(25);
        assertThat(ids(page(engineer, "?view=MINE"))).containsExactly(engineerTicket);
        assertThat(ids(page(engineer, "?view=ASSIGNED"))).containsExactly(mine.getFirst());
        assertThat(page(engineer, "?view=UNASSIGNED").path("total").asInt()).isEqualTo(24);
        assertThat(page(employee, "?page=999").path("items")).isEmpty();
    }

    @Test
    void combinedFiltersAndLiteralCaseInsensitiveSearchDoNotBroadenVisibility() throws Exception {
        assertThat(ids(page(employee, "?status=IN_PROGRESS&businessType=Network&q=OUTAGE"))).containsExactly(mine.getFirst());
        assertThat(ids(page(employee, "?q=vPn"))).containsExactly(mine.get(1));
        assertThat(page(employee, "?businessType=Net").path("total").asInt()).isZero();
        for (String literal : List.of("%", "_path", "\\"))
            assertThat(ids(page(employee, "?q=" + query(literal)))).containsExactly(mine.get(1));
        assertThat(page(employee, "?q=" + query("' OR 1=1 --")).path("total").asInt()).isZero();
        var detail = data(call(engineer, "GET", "/api/v1/tickets/" + engineerTicket, null));
        assertThat(ids(page(engineer, "?view=ALL&q=" + query(detail.path("number").asText())))).containsExactly(engineerTicket);
    }

    @Test
    void invalidQueriesReturnTheDocumentedCode() throws Exception {
        for (String invalid : List.of("page=0", "page=-1", "page=abc", "page=999999999999", "page=", "view=BAD", "view=", "status=BAD", "status=", "businessType=%20", "q=", "q=%20", "q=" + "x".repeat(101)))
            error(call(employee, "GET", "/api/v1/tickets?" + invalid, null), 400, "INVALID_QUERY");
        error(call(employee, "GET", "/api/v1/tickets/not-a-uuid", null), 400, "VALIDATION_FAILED");
    }

    @Test
    void detailExposesPublicSummaryAndNamesWithoutSensitiveFieldsOrSideEffects() throws Exception {
        long auditsBefore = db.queryForObject("SELECT count(*) FROM audit_event", Long.class);
        var response = call(employee, "GET", "/api/v1/tickets/" + mine.getFirst(), null);
        assertThat(response.statusCode()).isEqualTo(200);
        var value = data(response);
        assertThat(value.path("requesterName").asText()).isEqualTo("M05 员工");
        assertThat(value.path("assigneeName").asText()).isEqualTo("M05 工程师");
        assertThat(value.path("version").asLong()).isEqualTo(3);
        assertThat(value.path("priority").asText()).isEqualTo("P3");
        var resolved = call(employee, "GET", "/api/v1/tickets/" + mine.get(1), null);
        assertThat(data(resolved).path("resolutionSummary").asText()).isEqualTo("Public network solution");
        assertThat(data(resolved).path("assigneeId").isNull()).isTrue();
        assertThat(response.body()).doesNotContain("password", "csrf", "authVersion", "email");
        assertThat(value.has("sla")).isFalse();

        assertThat(db.queryForObject("SELECT count(*) FROM audit_event", Long.class)).isEqualTo(auditsBefore);
        error(call(new Browser(HttpClient.newHttpClient(), null), "GET", "/api/v1/tickets", null), 401, "SESSION_EXPIRED");
        for (String health : List.of("live", "ready")) assertThat(call(admin, "GET", "/api/v1/auth/csrf", null).statusCode()).isEqualTo(200);
    }
}
