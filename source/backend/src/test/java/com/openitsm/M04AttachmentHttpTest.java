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
import java.nio.file.Path;
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
class M04AttachmentHttpTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.openitsm.config.AccountMailSchedulingConfig mailScheduler;

    static final Path STORAGE_ROOT = temporaryStorage();
    static Path temporaryStorage() { try { return java.nio.file.Files.createTempDirectory("openitsm-attachment-test-"); } catch (java.io.IOException ex) { throw new ExceptionInInitializerError(ex); } }
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry props) { M01IntegrationTest.properties(props); props.add("openitsm.attachments.root", () -> STORAGE_ROOT.toString()); }

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
        db.execute("TRUNCATE notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
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

    long files() throws Exception { try (var files = java.nio.file.Files.list(STORAGE_ROOT)) { return files.count(); } }
    String field(String name, String value) {
        return "--m04-boundary\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
    }
    HttpResponse<String> upload(Browser actor, UUID id, String visibility, String name, byte[] bytes, String key, String version,
        String extra, boolean chunked) throws Exception {
        String start = field("visibility", visibility) + extra
            + "--m04-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
            + "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
        var body = HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofString(start),
            HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofString("\r\n--m04-boundary--\r\n"));
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path(id, "attachments")))
            .timeout(Duration.ofSeconds(25)).header("Origin", "http://localhost:5173")
            .header("Content-Type", "multipart/form-data; boundary=m04-boundary");
        if (actor.csrf() != null) builder.header("X-CSRF-Token", actor.csrf());


        builder.POST(chunked ? HttpRequest.BodyPublishers.fromPublisher(body) : body);
        return actor.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpResponse<String> upload(Browser actor, UUID id, String visibility, String name, byte[] bytes, long version) throws Exception {
        return upload(actor, id, visibility, name, bytes, UUID.randomUUID().toString(), "\"" + version + "\"", "", false);
    }
    String attachmentId(Browser actor, UUID id, String name) throws Exception {
        for (var item : page(actor, id, "attachments").path("items"))
            if (item.path("fileName").asText().equals(name)) return item.path("id").asText();
        throw new AssertionError("Missing attachment " + name);
    }
    HttpResponse<byte[]> download(Browser actor, String id) throws Exception {
        return actor.client().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v1/attachments/" + id)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    @Test
    void uploadsAndDownloadsEnforceRolesVisibilityAndClosedState() throws Exception {
        byte[] bytes = new byte[] {0, 1, 2, -1, 13, 10};
        for (String state : List.of("NEW", "IN_PROGRESS", "ON_HOLD", "RESOLVED")) {
            UUID id = ticket(); db.update("UPDATE ticket SET status=? WHERE id=?", state, id);
            ok(upload(employee, id, "PUBLIC", "public.bin", bytes, 0), 1);
            ok(upload(engineer, id, "INTERNAL", "private.bin", bytes, 1), 2);
            ok(upload(admin, id, "INTERNAL", "admin.bin", bytes, 2), 3);
            var visible = page(employee, id, "attachments");
            assertThat(visible.path("total").asInt()).isEqualTo(1);
            assertThat(visible.toString()).doesNotContain("private.bin", "admin.bin", "contentHash", "storage", "INTERNAL");
            assertThat(page(admin, id, "attachments").path("total").asInt()).isEqualTo(3);
            assertThat(page(employee, id, "operations").path("total").asInt()).isEqualTo(1);
            assertThat(page(engineer, id, "operations").path("total").asInt()).isEqualTo(3);
            String publicId = attachmentId(employee, id, "public.bin"), privateId = attachmentId(admin, id, "private.bin");
            for (Browser actor : List.of(employee, engineer, admin)) {
                var response = download(actor, publicId);
                assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).isEqualTo(bytes);
                assertThat(response.headers().firstValue("Content-Type")).contains("application/octet-stream");
                assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
                assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            }
            assertThat(download(employee, privateId).statusCode()).isEqualTo(404);
            assertThat(download(stranger, publicId).statusCode()).isEqualTo(404);
            assertThat(download(admin, privateId).statusCode()).isEqualTo(200);
            assertThat(download(engineer, privateId).statusCode()).isEqualTo(200);
            db.update("UPDATE ticket SET status='CLOSED' WHERE id=?", id);
            error(upload(employee, id, "PUBLIC", "closed.bin", bytes, 3), 409, "INVALID_STATE");
            assertThat(download(employee, publicId).statusCode()).isEqualTo(200);
            assertThat(version(id)).isEqualTo(3);
        }
    }

    @Test
    void exactLimitAndChunkedBodiesRespectMultipartExceptionWithoutWeakeningJsonLimits() throws Exception {
        UUID id = ticket();
        byte[] limit = new byte[10 * 1024 * 1024]; limit[limit.length - 1] = 7;
        ok(upload(employee, id, "PUBLIC", "limit.bin", limit, 0), 1);
        assertThat(download(employee, attachmentId(employee, id, "limit.bin")).body()).isEqualTo(limit);
        error(upload(employee, id, "PUBLIC", "empty.bin", new byte[0], 1), 400, "VALIDATION_FAILED");
        error(upload(employee, id, "PUBLIC", "large.bin", new byte[limit.length + 1], 1), 413, "REQUEST_TOO_LARGE");
        error(upload(employee, id, "PUBLIC", "chunked-large.bin", new byte[limit.length + 1], UUID.randomUUID().toString(), "\"1\"", "", true), 413, "REQUEST_TOO_LARGE");
        ok(upload(employee, id, "PUBLIC", "chunked.bin", new byte[300 * 1024], UUID.randomUUID().toString(), "\"1\"", "", true), 2);
        error(post(employee, id, "replies", Map.of("content", "x".repeat(256 * 1024)), 2), 413, "REQUEST_TOO_LARGE");
        assertThat(page(employee, id, "attachments").path("total").asInt()).isEqualTo(2);
    }

    @Test
    void multipartShapeSessionCsrfAndAccessFailuresDoNotCreateFiles() throws Exception {
        UUID id = ticket(); long before = files(); byte[] bytes = {1}; String key = UUID.randomUUID().toString();
        error(upload(employee, id, "INTERNAL", "denied", bytes, 0), 403, "FORBIDDEN");
        error(upload(stranger, id, "PUBLIC", "denied", bytes, 0), 404, "RESOURCE_NOT_FOUND");
        error(upload(admin, UUID.randomUUID(), "PUBLIC", "missing", bytes, 0), 404, "RESOURCE_NOT_FOUND");
        for (String extra : List.of(field("unexpected", "x"), field("visibility", "PUBLIC"), field("file", "not a file")))
            error(upload(employee, id, "PUBLIC", "one", bytes, key, "\"0\"", extra, false), 400, "VALIDATION_FAILED");
        error(upload(employee, id, "invalid", "one", bytes, 0), 400, "VALIDATION_FAILED");
        error(upload(new Browser(employee.client(), "x".repeat(64)), id, "PUBLIC", "one", bytes, 0), 403, "CSRF_INVALID");
        error(upload(new Browser(HttpClient.newHttpClient(), null), id, "PUBLIC", "one", bytes, 0), 401, "SESSION_EXPIRED");
        error(call(stranger, "GET", path(id, "attachments"), null, null, null), 404, "RESOURCE_NOT_FOUND");
        assertThat(download(employee, UUID.randomUUID().toString()).statusCode()).isEqualTo(404);
        assertThat(files()).isEqualTo(before); assertThat(count("ticket_attachment")).isZero(); assertThat(version(id)).isZero();
    }



    @Test
    void paginationFiltersBeforeCountingAndReadsDoNotWriteOrTouchSession() throws Exception {
        UUID id = ticket();
        for (int i = 0; i < 23; i++) ok(upload(employee, id, "PUBLIC", "public-" + i, new byte[]{1}, i), i + 1);
        ok(upload(engineer, id, "INTERNAL", "private", new byte[]{2}, 23), 24);
        var activity = db.queryForList("SELECT token_hash,last_user_activity_at FROM auth_session ORDER BY token_hash");
        long audit = count("audit_event");
        assertThat(page(employee, id, "attachments").path("items").size()).isEqualTo(20);
        var second = page(employee, id, "attachments?page=2");
        assertThat(second.path("total").asInt()).isEqualTo(23); assertThat(second.path("items").size()).isEqualTo(3);
        assertThat(page(admin, id, "attachments?page=2").path("items").size()).isEqualTo(4);
        assertThat(page(employee, id, "attachments?page=99").path("items").isEmpty()).isTrue();
        for (String invalid : List.of("0", "-1", "abc", "2147483648", ""))
            error(call(employee, "GET", path(id, "attachments?page=" + invalid), null, null, null), 400, "INVALID_QUERY");
        assertThat(count("audit_event")).isEqualTo(audit);
        assertThat(db.queryForList("SELECT token_hash,last_user_activity_at FROM auth_session ORDER BY token_hash")).isEqualTo(activity);
    }

    @Test
    void rollbackCleansOnlyItsGeneratedFileAndFilenameCannotEscapeStorage() throws Exception {
        UUID id = ticket(); long before = files(); byte[] bytes = {1, 2};
        db.execute("ALTER TABLE audit_event ADD CONSTRAINT m04_attachment_reject CHECK (action <> 'TICKET_ATTACHMENT')");
        try {
            error(upload(employee, id, "PUBLIC", "rollback.bin", bytes, 0), 503, "DEPENDENCY_UNAVAILABLE");
            assertThat(files()).isEqualTo(before); assertThat(count("ticket_attachment")).isZero();
            assertThat(version(id)).isZero(); assertThat(count("audit_event")).isZero();
        } finally { db.execute("ALTER TABLE audit_event DROP CONSTRAINT m04_attachment_reject"); }
        ok(upload(employee, id, "PUBLIC", "../../escape.html", bytes, 0), 1);
        String attachment = attachmentId(employee, id, "escape.html");
        assertThat(java.nio.file.Files.readAllBytes(STORAGE_ROOT.resolve(attachment))).isEqualTo(bytes);
        assertThat(java.nio.file.Files.exists(STORAGE_ROOT.resolve("escape.html"))).isFalse();
        ok(upload(engineer, id, "PUBLIC", "网络日志.txt", bytes, 1), 2);
        var response = download(employee, attachmentId(employee, id, "网络日志.txt"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Disposition").orElseThrow()).contains("filename*=UTF-8''");
        assertThat(files()).isEqualTo(before + 2);
    }

    @Test
    void concurrentAppendUploadsBothPersistWithoutOverwritingFiles() throws Exception {
        UUID id = ticket(); long before = files(); var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> { start.await(); return upload(employee, id, "PUBLIC", "one", new byte[]{1}, 0); });
            var two = pool.submit(() -> { start.await(); return upload(engineer, id, "INTERNAL", "two", new byte[]{2}, 0); });
            start.countDown();
            var responses = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));
            assertThat(responses.stream().map(HttpResponse::statusCode).toList()).containsExactlyInAnyOrder(200, 200);
        }
        assertThat(files()).isEqualTo(before + 2); assertThat(count("ticket_attachment")).isEqualTo(2);
        assertThat(count("audit_event")).isEqualTo(2); assertThat(version(id)).isEqualTo(2);
    }

    @Test
    void missingStorageFileAndVolumeAreReportedAsDependencyFailure() throws Exception {
        UUID id = ticket(); ok(upload(employee, id, "PUBLIC", "missing.bin", new byte[]{1}, 0), 1);
        String attachment = attachmentId(employee, id, "missing.bin");
        Path file = STORAGE_ROOT.resolve(attachment), moved = STORAGE_ROOT.resolve(attachment + ".saved");
        java.nio.file.Files.move(file, moved);
        try { assertThat(download(employee, attachment).statusCode()).isEqualTo(503); }
        finally { java.nio.file.Files.move(moved, file); }
        Path displaced = STORAGE_ROOT.resolveSibling(STORAGE_ROOT.getFileName() + "-displaced");
        java.nio.file.Files.move(STORAGE_ROOT, displaced);
        try {
            error(upload(employee, id, "PUBLIC", "unavailable", new byte[]{1}, 1), 503, "DEPENDENCY_UNAVAILABLE");
            assertThat(version(id)).isEqualTo(1);
        } finally { java.nio.file.Files.move(displaced, STORAGE_ROOT); }
    }

    @Test
    void v5UpgradePreservesExistingTicketReplyAndAuditData() {
        String schema = "m04_attachment_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("5").load().migrate();
        UUID user = UUID.randomUUID(), id = UUID.randomUUID(), reply = UUID.randomUUID(), event = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'upgrade@example.com','Upgrade','EMPLOYEE','INVITED')", user);
        db.update("INSERT INTO " + schema + ".ticket(id,title,description,business_type,impact_scope,requester_id,version) VALUES (?,'Original','Description','IT','Test',?,7)", id, user);
        db.update("INSERT INTO " + schema + ".ticket_reply(id,ticket_id,author_id,content,visibility,created_at) VALUES (?,?,?,'Existing reply','PUBLIC',CURRENT_TIMESTAMP)", reply, id, user);
        db.update("INSERT INTO " + schema + ".audit_event(id,actor_id,action,object_type,object_id,before_after,outcome,request_id,created_at) VALUES (?,?,'TICKET_REPLY','TICKET',?,'{}','SUCCESS',?,CURRENT_TIMESTAMP)", event, user, id, UUID.randomUUID());
        var ticketBefore = db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id);
        var replyBefore = db.queryForMap("SELECT * FROM " + schema + ".ticket_reply WHERE id=?", reply);
        var auditBefore = db.queryForMap("SELECT * FROM " + schema + ".audit_event WHERE id=?", event);
        assertThat(config.target("6").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".ticket WHERE id=?", id)).isEqualTo(ticketBefore);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".ticket_reply WHERE id=?", reply)).isEqualTo(replyBefore);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".audit_event WHERE id=?", event)).isEqualTo(auditBefore);
        assertThat(db.queryForObject("SELECT count(*) FROM " + schema + ".ticket_attachment", Long.class)).isZero();
        assertThat(config.target("6").load().migrate().migrationsExecuted).isZero();
    }
}
