package com.openitsm;

import com.openitsm.service.RateLimitService;
import com.openitsm.service.impl.AccountMailCipher;
import com.openitsm.common.utils.TokenCodec;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.service.AuthService;
import com.openitsm.service.AuthContext;
import com.openitsm.service.BootstrapService;
import com.openitsm.common.utils.Values;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.UserService;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.sql.DriverManager;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.*;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class M01IntegrationTest {
    static { System.setProperty("api.version", "1.44"); }
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11");
    static String testDatabase;
    static final byte[] KEY = Base64.getDecoder().decode("bTAxLWludGVncmF0aW9uLXRlc3Qta2V5LTAwMDAwMDA="); // Test-only key, never deployed.
    static final Path KEY_FILE;
    static {
        try { KEY_FILE = Files.createTempFile("openitsm-m01-test-", ".key"); Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(KEY)); }
        catch (Exception ex) { throw new ExceptionInInitializerError(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
        props.add("openitsm.account-mail.enabled", () -> false);
        String adminUrl = System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL");
        if (adminUrl == null || adminUrl.isBlank()) {
            PG.start();
            Runtime.getRuntime().addShutdownHook(new Thread(PG::stop));
            props.add("spring.datasource.url", PG::getJdbcUrl);
            props.add("spring.datasource.username", PG::getUsername);
            props.add("spring.datasource.password", PG::getPassword);
        } else {
            String user = System.getenv("OPENITSM_TEST_DB_USER");
            String password = System.getenv("OPENITSM_TEST_DB_PASSWORD");
            testDatabase = System.getenv("OPENITSM_TEST_DATABASE");
            if (testDatabase == null) {
                testDatabase = "openitsm_m01_test_" + UUID.randomUUID().toString().replace("-", "");
                try (var connection = DriverManager.getConnection(adminUrl, user, password); var sql = connection.createStatement()) {
                    sql.execute("CREATE DATABASE " + testDatabase);
                } catch (Exception ex) { throw new IllegalStateException("Cannot create isolated M01 test database", ex); }
            } else if (!testDatabase.matches("openitsm_m01_test_[a-f0-9]{32}")) {
                throw new IllegalStateException("Only an explicitly named isolated M01 test database may be reused");
            }
            String testUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + testDatabase;
            props.add("spring.datasource.url", () -> testUrl);
            props.add("spring.datasource.username", () -> user);
            props.add("spring.datasource.password", () -> password);
            System.out.println("Isolated M01 test database retained: " + testDatabase);
        }
        props.add("openitsm.cookie-secure", () -> false);
        props.add("openitsm.cookie-name", () -> "openitsm-test-session");
        props.add("openitsm.origin", () -> "http://localhost:5173");
        props.add("openitsm.account-mail-key-file", KEY_FILE::toString);
    }

    static class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-14T02:00:00Z");
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary TestClock testClock() { return new TestClock(); } }

    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired UserMapper users;
    @Autowired PasswordEncoder encoder;
    @Autowired BootstrapService bootstrap;
    @Autowired AuthService auth;
    @Autowired UserService members;
    @Autowired RateLimitService rates;
    @Autowired TestClock clock;
    String password = "TestPassword1234";
    UUID admin;
    record Browser(Cookie cookie, String csrf) {}

    @BeforeEach void setUp() {
        clock.now = Instant.parse("2026-10-14T02:00:00Z");
        // Only this disposable container's test database is reset, never a configured user database.
        db.execute("TRUNCATE idempotency_record,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        db.execute("TRUNCATE ticket_attachment,ticket_reply,notification_task,ticket");
        admin = bootstrap.initialize("Admin", "admin@example.com", password);
        TicketTypeTestSupport.type(db, "Test");
    }

    Browser anonymous() throws Exception {
        var result = http.perform(get("/api/v1/auth/csrf")).andReturn();
        assertStatus(result, 200);
        return new Browser(result.getResponse().getCookie("openitsm-test-session"), data(result).get("csrfToken").asText());
    }
    MockHttpServletRequestBuilder write(MockHttpServletRequestBuilder req, Browser browser) {
        return req.cookie(browser.cookie()).header("Origin", "http://localhost:5173").header("X-CSRF-Token", browser.csrf()).contentType("application/json");
    }
    MvcResult login(Browser browser, String email, String password, String returnTo) throws Exception {
        var body = new LinkedHashMap<String, Object>(); body.put("email", email); body.put("password", password);
        if (returnTo != null) body.put("returnTo", returnTo);
        return http.perform(write(post("/api/v1/auth/login"), browser).content(json.writeValueAsString(body))).andReturn();
    }
    Browser signed(String email) throws Exception {
        MvcResult result = login(anonymous(), email, password, null);
        assertStatus(result, 200);
        return new Browser(result.getResponse().getCookie("openitsm-test-session"), data(result).get("csrfToken").asText());
    }
    JsonNode data(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).get("data"); }
    String code(MvcResult result) throws Exception { return json.readTree(result.getResponse().getContentAsString()).path("error").path("code").asText(); }
    void assertStatus(MvcResult result, int status) throws Exception {
        assertThat(result.getResponse().getStatus()).as(result.getResponse().getContentAsString()).isEqualTo(status);
        assertThat(result.getResponse().getHeader("X-Request-ID")).isNotBlank();
    }
    UUID active(String email, String role) {
        UserEntity u = new UserEntity(); u.id = UUID.randomUUID(); u.name = role; u.emailNormalized = email;
        u.role = role; u.status = "ACTIVE"; u.passwordHash = encoder.encode(password); u.updatedAt = clock.instant();
        users.insert(u); return u.id;
    }
    UUID invite(Browser browser, String email, String role) throws Exception {
        MvcResult result = http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", UUID.randomUUID())
            .content(json.writeValueAsString(Map.of("name", "Invited", "email", email, "role", role)))).andReturn();
        assertStatus(result, 201); return UUID.fromString(data(result).get("resourceId").asText());
    }
    String mailedToken(UUID userId, String purpose) throws Exception {
        var row = db.queryForMap("SELECT sealed_payload,token_generation FROM notification_task WHERE recipient_id=? AND template=? AND status='PENDING'", userId, purpose);
        String[] sealed = row.get("sealed_payload").toString().split("\\.");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(KEY, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(sealed[0])));
        cipher.updateAAD((userId + ":" + purpose + ":" + row.get("token_generation")).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.getDecoder().decode(sealed[1])), java.nio.charset.StandardCharsets.UTF_8);
    }
    MvcResult consume(Browser browser, String route, String token, String pass) throws Exception {
        return http.perform(write(post("/api/v1/auth/" + route), browser)
            .content(json.writeValueAsString(Map.of("token", token, "password", pass, "confirmPassword", pass)))).andReturn();
    }
    MvcResult memberAction(Browser browser, UUID id, String action, long version, String body) throws Exception {
        return http.perform(write(post("/api/v1/users/" + id + "/" + action), browser)
            .header("If-Match", "\"" + version + "\"").header("Idempotency-Key", UUID.randomUUID()).content(body == null ? "" : body)).andReturn();
    }

    @Test void allRolesCanReturnToCreateTicketAfterLogin() throws Exception {
        active("employee-create@example.com", "EMPLOYEE");
        active("engineer-create@example.com", "ENGINEER");
        for (String email : List.of("admin@example.com", "employee-create@example.com", "engineer-create@example.com")) {
            var result = login(anonymous(), email, password, "/tickets/new");
            assertStatus(result, 200);
            assertThat(data(result).get("redirectPath").asText()).isEqualTo("/tickets/new");
        }
    }

    @Test void databaseAndMigrationAreRealPostgres17() {
        assertThat(db.queryForObject("SHOW server_version", String.class)).startsWith("17.");
        assertThat(db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(14);
        assertThat(db.queryForObject("SELECT count(*) FROM account_bootstrap", Integer.class)).isEqualTo(1);
        assertThatThrownBy(() -> bootstrap.initialize("Second", "second@example.com", password)).isInstanceOf(BusinessException.class).hasMessage("初始化入口已关闭");
    }

    @Test void loginValidatesFieldsAndRejectsUnknownWrites() throws Exception {
        Browser anon = anonymous();
        for (var body : List.of(Map.of("email", "", "password", ""), Map.of("email", "bad", "password", password), Map.of("email", "admin@example.com", "password", ""))) {
            var result = http.perform(write(post("/api/v1/auth/login"), anon).content(json.writeValueAsString(body))).andReturn();
            assertStatus(result, 400); assertThat(code(result)).isEqualTo("VALIDATION_FAILED");
        }
        var result = http.perform(write(post("/api/v1/auth/login"), anon).content("{\"email\":\"admin@example.com\",\"password\":\"TestPassword1234\",\"role\":\"ADMIN\"}")).andReturn();
        assertStatus(result, 400);
    }

    @Test void loginRotatesCookieAndCsrfAndChoosesRoleRoutes() throws Exception {
        active("employee@example.com", "EMPLOYEE"); active("engineer@example.com", "ENGINEER");
        Map<String, String> routes = Map.of("admin@example.com", "/dashboard", "employee@example.com", "/tickets?view=MINE", "engineer@example.com", "/tickets?view=ASSIGNED");
        for (var entry : routes.entrySet()) {
            Browser anon = anonymous(); MvcResult result = login(anon, entry.getKey().toUpperCase(Locale.ROOT), password, "https://evil.example");
            assertStatus(result, 200);
            assertThat(data(result).get("defaultPath").asText()).isEqualTo(entry.getValue());
            assertThat(data(result).get("redirectPath").asText()).isEqualTo(entry.getValue());
            assertThat(result.getResponse().getCookie("openitsm-test-session").getValue()).isNotEqualTo(anon.cookie().getValue());
            assertThat(data(result).get("csrfToken").asText()).isNotEqualTo(anon.csrf());
            assertThat(result.getResponse().getHeader("Set-Cookie")).contains("HttpOnly", "SameSite=Lax", "Path=/");
            assertThat(db.queryForObject("SELECT count(*) FROM auth_session WHERE token_hash=?", Integer.class, anon.cookie().getValue())).isZero();
        }
        var allowed = login(anonymous(), "employee@example.com", password, "/knowledge");
        assertThat(data(allowed).get("redirectPath").asText()).isEqualTo("/knowledge");
        var denied = login(anonymous(), "employee@example.com", password, "/dashboard");
        assertThat(data(denied).get("redirectPath").asText()).isEqualTo("/tickets?view=MINE");
    }

    @Test void fiveFailuresLockWithoutExtendingAndExpiryClears() throws Exception {
        Browser anon = anonymous();
        for (int i = 0; i < 5; i++) assertStatus(login(anon, "admin@example.com", "WrongPassword123", null), 401);
        UserEntity locked = users.selectById(admin);
        assertThat(locked.failedCount).isEqualTo(5);
        assertThat(locked.lockedUntil).isEqualTo(clock.instant().plusSeconds(900));
        clock.advance(10);
        assertStatus(login(anon, "admin@example.com", password, null), 401);
        assertThat(users.selectById(admin).lockedUntil).isEqualTo(locked.lockedUntil);
        assertStatus(login(anon, "absent@example.com", password, null), 401);
        clock.advance(890);
        assertStatus(login(anon, "admin@example.com", password, null), 200);
        assertThat(users.selectById(admin).failedCount).isZero(); assertThat(users.selectById(admin).lockedUntil).isNull();
    }

    @Test void concurrentFailuresDoNotLoseCounts() throws Exception {
        try (var pool = Executors.newFixedThreadPool(5)) {
            var futures = new ArrayList<Future<?>>();
            for (int i = 0; i < 5; i++) futures.add(pool.submit(() -> auth.login("admin@example.com", "WrongPassword123", null)));
            for (var future : futures) assertThat(future.get(30, TimeUnit.SECONDS)).isNull();
        }
        assertThat(users.selectById(admin).failedCount).isEqualTo(5);
        assertThat(users.selectById(admin).lockedUntil).isNotNull();
    }

    @Test void csrfOriginRoleAndPollingAreEnforced() throws Exception {
        Browser anon = anonymous();
        assertStatus(http.perform(post("/api/v1/auth/login").cookie(anon.cookie()).contentType("application/json").content("{}")).andReturn(), 403);
        assertStatus(http.perform(write(post("/api/v1/auth/login"), anon).with(request -> {
            request.removeHeader("Origin"); request.addHeader("Origin", "https://evil.example"); return request;
        }).content("{}")).andReturn(), 403);
        active("employee@example.com", "EMPLOYEE"); Browser employee = signed("employee@example.com");
        assertStatus(http.perform(get("/api/v1/users").cookie(employee.cookie())).andReturn(), 403);
        assertStatus(http.perform(get("/api/v1/users/assignees").cookie(employee.cookie())).andReturn(), 200);
        clock.advance(7199);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(employee.cookie())).andReturn(), 200);
        clock.advance(1);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(employee.cookie())).andReturn(), 401);
        assertStatus(http.perform(write(post("/api/v1/auth/activity"), employee)).andReturn(), 401);
    }

    @Test void activityRateAndAbsoluteTimeoutAndLogout() throws Exception {
        Browser browser = signed("admin@example.com");
        assertStatus(http.perform(write(post("/api/v1/auth/activity"), browser)).andReturn(), 204);
        assertStatus(http.perform(write(post("/api/v1/auth/activity"), browser)).andReturn(), 429);
        for (int i = 0; i < 23; i++) { clock.advance(3600); assertStatus(http.perform(write(post("/api/v1/auth/activity"), browser)).andReturn(), 204); }
        clock.advance(3600);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(browser.cookie())).andReturn(), 401);
        // Logout remains safe and idempotent even for expired/revoked sessions.
        assertStatus(http.perform(write(post("/api/v1/auth/logout"), browser)).andReturn(), 204);
        assertStatus(http.perform(write(post("/api/v1/auth/logout"), browser)).andReturn(), 204);
        Browser fresh = signed("admin@example.com");
        assertStatus(http.perform(write(post("/api/v1/auth/logout"), fresh)).andReturn(), 204);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(fresh.cookie())).andReturn(), 401);
    }

    @Test void invitationIsAtomicSingleUseEncryptedAndPurposeScoped() throws Exception {
        UUID user = invite(signed("admin@example.com"), "new@example.com", "ENGINEER");
        String raw = mailedToken(user, "INVITE");
        assertThat(db.queryForObject("SELECT token_hash FROM account_token WHERE user_id=?", String.class, user)).isEqualTo(TokenCodec.hash(raw));
        assertThat(db.queryForObject("SELECT sealed_payload FROM notification_task WHERE recipient_id=?", String.class, user)).doesNotContain(raw);
        Browser anon = anonymous();
        assertStatus(consume(anon, "password-resets", raw, password), 409);
        assertStatus(login(anon, "new@example.com", password, null), 401);
        assertStatus(consume(anon, "activations", raw, password), 200);
        assertStatus(consume(anon, "activations", raw, password), 409);
        assertStatus(login(anon, "new@example.com", password, null), 200);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE recipient_id=? AND error_code='TASK_CANCELLED'", Integer.class, user)).isEqualTo(1);
    }

    @Test void resendInvalidatesOldGenerationAndExpiryRejects() throws Exception {
        Browser adminBrowser = signed("admin@example.com"); UUID user = invite(adminBrowser, "new@example.com", "EMPLOYEE");
        String old = mailedToken(user, "INVITE");
        assertStatus(memberAction(adminBrowser, user, "invitation-resend", 0, null), 429);
        clock.advance(60);
        assertStatus(memberAction(adminBrowser, user, "invitation-resend", 0, null), 202);
        String current = mailedToken(user, "INVITE"); assertThat(current).isNotEqualTo(old);
        Browser anon = anonymous(); assertStatus(consume(anon, "activations", old, password), 409);
        clock.advance(48 * 3600);
        assertStatus(consume(anonymous(), "activations", current, password), 409);
    }

    @Test void resetIsUniformRateLimitedAndRevokesOldCredentialsSessionsAndLock() throws Exception {
        Browser adminBrowser = signed("admin@example.com"); Browser anon = anonymous();
        for (String email : List.of("admin@example.com", "missing@example.com")) {
            var result = http.perform(write(post("/api/v1/auth/password-reset-requests"), anon).content(json.writeValueAsString(Map.of("email", email)))).andReturn();
            assertStatus(result, 202); assertThat(data(result).get("message").asText()).isEqualTo("如果该邮箱对应可用账号，你将收到重置邮件");
            assertStatus(http.perform(write(post("/api/v1/auth/password-reset-requests"), anon).content(json.writeValueAsString(Map.of("email", email)))).andReturn(), 429);
        }
        String raw = mailedToken(admin, "RESET");
        for (int i = 0; i < 5; i++) auth.login("admin@example.com", "WrongPassword123", null);
        String nextPassword = "DifferentPassword5678";
        assertStatus(consume(anon, "password-resets", raw, nextPassword), 200);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(adminBrowser.cookie())).andReturn(), 401);
        assertStatus(consume(anon, "password-resets", raw, nextPassword), 409);
        assertStatus(login(anon, "admin@example.com", password, null), 401);
        assertStatus(login(anon, "admin@example.com", nextPassword, null), 200);
        assertThat(users.selectById(admin).lockedUntil).isNull();
    }

    @Test void resetHourlyLimitNewLinkAndExpiry() throws Exception {
        Browser anon = anonymous();
        String old = null;
        for (int i = 0; i < 5; i++) {
            assertStatus(http.perform(write(post("/api/v1/auth/password-reset-requests"), anon).content("{\"email\":\"admin@example.com\"}")).andReturn(), 202);
            if (i == 0) old = mailedToken(admin, "RESET");
            clock.advance(60);
        }
        assertStatus(http.perform(write(post("/api/v1/auth/password-reset-requests"), anon).content("{\"email\":\"admin@example.com\"}")).andReturn(), 429);
        assertStatus(consume(anon, "password-resets", old, password), 409);
        String current = mailedToken(admin, "RESET"); clock.advance(1800);
        assertStatus(consume(anon, "password-resets", current, password), 409);
    }

    @Test void memberVersionIdempotencyAndAuditAreEnforced() throws Exception {
        Browser browser = signed("admin@example.com"); UUID key = UUID.randomUUID();
        String body = "{\"name\":\"New\",\"email\":\"new@example.com\",\"role\":\"EMPLOYEE\"}";
        var first = http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", key).content(body)).andReturn(); assertStatus(first, 201);
        var replay = http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", key).content("{\"role\":\"EMPLOYEE\",\"email\":\"new@example.com\",\"name\":\"New\"}")).andReturn();
        assertStatus(replay, 201); assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
        UUID user = UUID.fromString(data(first).get("resourceId").asText());
        assertStatus(http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", key).content(body.replace("New", "Different"))).andReturn(), 409);
        assertStatus(http.perform(write(patch("/api/v1/users/" + user), browser).header("Idempotency-Key", UUID.randomUUID()).content("{\"name\":\"Changed\"}")).andReturn(), 428);
        assertStatus(memberAction(browser, user, "unlock", 0, null), 200);
        assertStatus(memberAction(browser, user, "unlock", 0, null), 409);
        assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE object_id=?", Integer.class, user)).isEqualTo(2);
        var list = http.perform(get("/api/v1/users").cookie(browser.cookie())).andReturn(); assertStatus(list, 200);
        assertThat(data(list).get("pageSize").asInt()).isEqualTo(20);
        assertThat(list.getResponse().getContentAsString()).doesNotContain("passwordHash", "failedCount", "token_hash");
    }

    @Test void memberWritesValidateVersionsBeforeReplayAndRejectChangedBodies() throws Exception {
        Browser browser = signed("admin@example.com");
        for (String action : List.of("PATCH", "unlock", "invitation-resend", "disable")) {
            UUID id = invite(browser, action.toLowerCase(Locale.ROOT) + "-contract@example.com", "EMPLOYEE");
            if (action.equals("invitation-resend")) clock.advance(60);
            String path = "/api/v1/users/" + id + (action.equals("PATCH") ? "" : "/" + action);
            String body = switch (action) {
                case "PATCH" -> "{\"name\":\"Changed\"}";
                case "disable" -> "{\"reason\":\"Leaving company\"}";
                default -> "";
            };
            UUID key = UUID.randomUUID();
            var missing = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                .header("Idempotency-Key", key).content(body)).andReturn();
            assertStatus(missing, 428);
            assertThat(code(missing)).isEqualTo("PRECONDITION_REQUIRED");
            var stale = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                .header("If-Match", "\"99\"").header("Idempotency-Key", key).content(body)).andReturn();
            assertStatus(stale, 409);
            assertThat(code(stale)).isEqualTo("VERSION_CONFLICT");
            var first = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                .header("If-Match", "\"0\"").header("Idempotency-Key", key).content(body)).andReturn();
            int success = action.equals("invitation-resend") ? 202 : 200;
            assertStatus(first, success);
            assertThat(data(first).get("acceptedVersion").asLong()).isEqualTo(1);
            var replay = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                .header("If-Match", "\"0\"").header("Idempotency-Key", key).content(body)).andReturn();
            assertStatus(replay, success);
            assertThat(replay.getResponse().getHeader("Idempotent-Replayed")).isEqualTo("true");
            assertThat(data(replay).get("replayed").asBoolean()).isTrue();
            var missingOnReplay = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                .header("Idempotency-Key", key).content(body)).andReturn();
            assertStatus(missingOnReplay, 428);
            assertThat(code(missingOnReplay)).isEqualTo("PRECONDITION_REQUIRED");
            var malformedOnReplay = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                .header("If-Match", "0").header("Idempotency-Key", key).content(body)).andReturn();
            assertStatus(malformedOnReplay, 400);
            assertThat(code(malformedOnReplay)).isEqualTo("VALIDATION_FAILED");
            if (!body.isEmpty()) {
                String changed = body.replace("Changed", "Different").replace("Leaving company", "Different reason");
                var conflict = http.perform(write(action.equals("PATCH") ? patch(path) : post(path), browser)
                    .header("If-Match", "\"0\"").header("Idempotency-Key", key).content(changed)).andReturn();
                assertStatus(conflict, 409);
                assertThat(code(conflict)).isEqualTo("IDEMPOTENCY_CONFLICT");
            }
            assertThat(users.selectById(id).version).isEqualTo(1);
            assertThat(db.queryForObject("SELECT count(*) FROM audit_event WHERE object_id=?", Integer.class, id)).isEqualTo(2);
            assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE recipient_id=?", Integer.class, id))
                .isEqualTo(action.equals("invitation-resend") ? 2 : 1);
            assertThat(db.queryForObject("SELECT count(*) FROM idempotency_record WHERE resource_id=?", Integer.class, id)).isEqualTo(2);
            if (action.equals("PATCH")) assertThat(users.selectById(id).name).isEqualTo("Changed");
            if (action.equals("disable")) assertThat(db.queryForObject(
                "SELECT reason FROM audit_event WHERE object_id=? AND action='MEMBER_DISABLE'", String.class, id)).isEqualTo("Leaving company");
        }
    }

    @Test void lastAdminHandoffDisableRoleRevocationAndUnlock() throws Exception {
        Browser browser = signed("admin@example.com");
        assertStatus(memberAction(browser, admin, "disable", 0, "{\"reason\":\"Leaving company\"}"), 409);
        UUID engineer = active("engineer@example.com", "ENGINEER"); Browser engineerBrowser = signed("engineer@example.com");
        db.update("INSERT INTO ticket (id,requester_id,assignee_id,status,title,description,business_type,impact_scope,business_type_id) VALUES (?,?,?,'RESOLVED','Test','Test','Test','Test','5716d812-6943-3c2f-9c7f-96424a156bbf')", UUID.randomUUID(), admin, engineer);
        var blocked = memberAction(browser, engineer, "disable", 0, "{\"reason\":\"Leaving company\"}");
        assertStatus(blocked, 409); assertThat(code(blocked)).isEqualTo("MEMBER_HAS_OPEN_TICKETS");
        assertThat(blocked.getResponse().getContentAsString()).contains("1");
        assertStatus(http.perform(write(patch("/api/v1/users/" + engineer), browser).header("If-Match", "\"0\"")
            .header("Idempotency-Key", UUID.randomUUID()).content("{\"role\":\"EMPLOYEE\"}")).andReturn(), 409);
        db.update("UPDATE ticket SET status='CLOSED' WHERE assignee_id=?", engineer);
        for (int i = 0; i < 5; i++) auth.login("engineer@example.com", "WrongPassword123", null);
        assertStatus(memberAction(browser, engineer, "unlock", 0, null), 200); assertThat(users.selectById(engineer).lockedUntil).isNull();
        assertStatus(memberAction(browser, engineer, "disable", 1, "{\"reason\":\"Leaving company\"}"), 200);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(engineerBrowser.cookie())).andReturn(), 401);
        assertStatus(login(anonymous(), "engineer@example.com", password, null), 401);
        UUID another = active("another@example.com", "ENGINEER"); Browser anotherBrowser = signed("another@example.com");
        assertStatus(http.perform(write(patch("/api/v1/users/" + another), browser).header("If-Match", "\"0\"")
            .header("Idempotency-Key", UUID.randomUUID()).content("{\"role\":\"EMPLOYEE\"}")).andReturn(), 200);
        assertStatus(http.perform(get("/api/v1/auth/me").cookie(anotherBrowser.cookie())).andReturn(), 401);
    }

    @Test void twoAdminsCannotConcurrentlyRemoveEachOther() throws Exception {
        UUID second = active("second@example.com", "ADMIN");
        var one = auth.login("admin@example.com", password, null); var two = auth.login("second@example.com", password, null);
        AuthContext c1 = new AuthContext(one.session(), one.user(), one.rawToken());
        AuthContext c2 = new AuthContext(two.session(), two.user(), two.rawToken());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var f1 = pool.submit(() -> changeResult(c1, second)); var f2 = pool.submit(() -> changeResult(c2, admin));
            assertThat(List.of(f1.get(30, TimeUnit.SECONDS), f2.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 403);
        }
        assertThat(users.activeAdmins()).isEqualTo(1);
    }
    int changeResult(AuthContext ctx, UUID target) {
        try { members.change(ctx, target, "disable", null, "Leaving company", "\"0\"", UUID.randomUUID().toString(), UUID.randomUUID()); return 200; }
        catch (BusinessException ex) { return ex.status; }
    }

    @Test void missingMailKeyRollsBackInviteAndDoesNotRevealResetIdentity() throws Exception {
        var badQueue = new AccountMailCipher("");
        assertThatThrownBy(badQueue::requireReady).isInstanceOf(BusinessException.class);
        // Transaction rollback is tested through a failing enqueue by temporarily moving the test key's content aside in memory.
        String saved = Files.readString(KEY_FILE);
        try {
            Files.writeString(KEY_FILE, "invalid-test-key");
            Browser browser = signed("admin@example.com");
            var invite = http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", UUID.randomUUID())
                .content("{\"name\":\"New\",\"email\":\"new@example.com\",\"role\":\"EMPLOYEE\"}")).andReturn();
            assertStatus(invite, 503);
            assertThat(users.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserEntity>().eq("email_normalized", "new@example.com"))).isZero();
            Browser anon = anonymous();
            for (String email : List.of("admin@example.com", "absent@example.com")) assertStatus(http.perform(write(post("/api/v1/auth/password-reset-requests"), anon).content(json.writeValueAsString(Map.of("email", email)))).andReturn(), 503);
        } finally { Files.writeString(KEY_FILE, saved); }
    }

    @Test void passwordUnicodeAndUnsafeRedirectInputsAreChecked() throws Exception {
        assertThatThrownBy(() -> Values.password("abcdefghijk", "abcdefghijk")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Values.password("abcdefghijkl", "abcdefghijkl")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Values.password("123456789012", "123456789012")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> Values.password(password, "Different1234")).isInstanceOf(BusinessException.class);
        assertThat(Values.password("中文密码包含字符长度１２３４５６", "中文密码包含字符长度１２３４５６")).isNotBlank();
        for (String path : List.of("//evil.example", "/\\evil.example", "/tickets?view=ALL", "/%2f%2fevil", "/dashboard#https://evil")) {
            activeIfMissing("employee@example.com");
            var result = login(anonymous(), "employee@example.com", password, path); assertStatus(result, 200);
            assertThat(data(result).get("redirectPath").asText()).isEqualTo("/tickets?view=MINE");
        }
    }
    void activeIfMissing(String email) { if (users.selectCount(new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<UserEntity>().eq("email_normalized", email)) == 0) active(email, "EMPLOYEE"); }

    @Test void legitimateOriginalTicketPageIsCheckedAgainstCurrentVisibility() throws Exception {
        UUID employee = active("employee@example.com", "EMPLOYEE");
        UUID ownTicket = UUID.randomUUID(); UUID otherTicket = UUID.randomUUID();
        db.update("INSERT INTO ticket (id,requester_id,title,description,business_type,impact_scope,business_type_id) VALUES (?,?,'Test','Test','Test','Test','5716d812-6943-3c2f-9c7f-96424a156bbf')", ownTicket, employee);
        db.update("INSERT INTO ticket (id,requester_id,title,description,business_type,impact_scope,business_type_id) VALUES (?,?,'Test','Test','Test','Test','5716d812-6943-3c2f-9c7f-96424a156bbf')", otherTicket, admin);
        var own = login(anonymous(), "employee@example.com", password, "/tickets/" + ownTicket);
        assertStatus(own, 200); assertThat(data(own).get("redirectPath").asText()).isEqualTo("/tickets/" + ownTicket);
        var other = login(anonymous(), "employee@example.com", password, "/tickets/" + otherTicket);
        assertStatus(other, 200); assertThat(data(other).get("redirectPath").asText()).isEqualTo("/tickets?view=MINE");
        var adminReturn = login(anonymous(), "admin@example.com", password, "/tickets/" + otherTicket);
        assertStatus(adminReturn, 200); assertThat(data(adminReturn).get("redirectPath").asText()).isEqualTo("/tickets/" + otherTicket);
    }

    @Test void sameKeyInFlightConflictIsImmediateEvenWithMemberLockHeld() throws Exception {
        Browser browser = signed("admin@example.com"); UUID key = UUID.randomUUID();
        try (var connection = db.getDataSource().getConnection()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                lock.setString(1, "IDEMPOTENCY:" + admin + ":POST /api/v1/users:" + key); lock.execute();
                lock.setString(1, "MEMBERS:CONTROL"); lock.execute();
            }
            var result = http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", key)
                .content("{\"name\":\"New\",\"email\":\"new@example.com\",\"role\":\"EMPLOYEE\"}")).andReturn();
            assertStatus(result, 409); assertThat(code(result)).isEqualTo("REQUEST_IN_PROGRESS");
            assertThat(result.getResponse().getHeader("Retry-After")).isEqualTo("1");
            connection.rollback();
        }
    }

    @Test void concurrentSingleUseActivationHasOnlyOneWinner() throws Exception {
        UUID user = invite(signed("admin@example.com"), "new@example.com", "EMPLOYEE");
        String raw = mailedToken(user, "INVITE");
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<Integer> attempt = () -> {
                try { auth.consume(raw, password, "INVITE", UUID.randomUUID()); return 200; }
                catch (BusinessException ex) { return ex.status; }
            };
            var first = pool.submit(attempt); var second = pool.submit(attempt);
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(users.selectById(user).version).isEqualTo(1);
    }

    @Test void concurrentSelfDisablesPreserveLastAdministrator() throws Exception {
        UUID second = active("second@example.com", "ADMIN");
        var one = auth.login("admin@example.com", password, null); var two = auth.login("second@example.com", password, null);
        AuthContext c1 = new AuthContext(one.session(), one.user(), one.rawToken());
        AuthContext c2 = new AuthContext(two.session(), two.user(), two.rawToken());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> changeResult(c1, admin)); var another = pool.submit(() -> changeResult(c2, second));
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), another.get(30, TimeUnit.SECONDS))).containsExactlyInAnyOrder(200, 409);
        }
        assertThat(users.activeAdmins()).isEqualTo(1);
    }

    @Test void memberDisableWaitsForAssignmentLockAndSeesCommittedTicket() throws Exception {
        UUID engineer = active("engineer@example.com", "ENGINEER");
        Browser browser = signed("admin@example.com");
        try (var connection = db.getDataSource().getConnection(); var pool = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            try (var lock = connection.prepareStatement("SELECT id FROM app_user WHERE id=? FOR UPDATE")) {
                lock.setObject(1, engineer); lock.execute();
            }
            try (var insert = connection.prepareStatement("INSERT INTO ticket (id,requester_id,assignee_id,title,description,business_type,impact_scope,business_type_id) VALUES (?,?,?,'Test','Test','Test','Test','5716d812-6943-3c2f-9c7f-96424a156bbf')")) {
                insert.setObject(1, UUID.randomUUID()); insert.setObject(2, admin); insert.setObject(3, engineer); insert.execute();
            }
            var pending = pool.submit(() -> memberAction(browser, engineer, "disable", 0, "{\"reason\":\"Leaving company\"}"));
            assertThatThrownBy(() -> pending.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            connection.commit();
            var result = pending.get(30, TimeUnit.SECONDS);
            assertStatus(result, 409); assertThat(code(result)).isEqualTo("MEMBER_HAS_OPEN_TICKETS");
        }
        assertThat(users.selectById(engineer).status).isEqualTo("ACTIVE");
    }

    @Test void loginSourceLimitDoesNotCountPasswordFailuresOrTrustForwardedHeader() throws Exception {
        Browser browser = anonymous();
        for (int i = 0; i < 60; i++) rates.check("LOGIN_SOURCE", "127.0.0.1", 60, Duration.ofMinutes(1), 0);
        var result = http.perform(write(post("/api/v1/auth/login"), browser).header("X-Forwarded-For", "198.51.100.3")
            .content("{\"email\":\"admin@example.com\",\"password\":\"WrongPassword123\"}")).andReturn();
        assertStatus(result, 429); assertThat(users.selectById(admin).failedCount).isZero();
    }

    @Test void migrationValidationAndSecondRunDoNotReapply() {
        var flyway = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).load();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Integer.class)).isEqualTo(14);
    }

    @Test void protocolErrorsAreClientErrorsAndIdempotencyCoversOriginalValues() throws Exception {
        Browser browser = signed("admin@example.com");
        assertStatus(http.perform(write(post("/api/v1/auth/me"), browser)).andReturn(), 405);
        assertStatus(http.perform(write(post("/api/v1/auth/login"), browser).contentType("text/plain").content("{}")).andReturn(), 415);
        UUID key = UUID.randomUUID();
        String body = "{\"name\":\"New\",\"email\":\"new@example.com\",\"role\":\"EMPLOYEE\"}";
        assertStatus(http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", key).content(body)).andReturn(), 201);
        var changed = http.perform(write(post("/api/v1/users"), browser).header("Idempotency-Key", key).content(body.replace("new@example.com", "NEW@example.com"))).andReturn();
        assertStatus(changed, 409); assertThat(code(changed)).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
}
