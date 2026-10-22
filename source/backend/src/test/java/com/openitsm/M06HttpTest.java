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
import static org.mockito.Mockito.*;
import com.openitsm.mapper.NotificationMapper;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.mapper.AuthMapper;
import com.openitsm.service.SmtpMailService;
import com.openitsm.service.AccountMailDispatchService;
import com.openitsm.service.impl.AccountMailDispatchServiceImpl;
import com.openitsm.service.impl.SmtpMailServiceImpl;

/** TCP HTTP against the actual Spring application and isolated PostgreSQL. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@org.springframework.context.annotation.Import(M01IntegrationTest.TimeConfig.class)
class M06HttpTest {
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
    @Autowired NotificationMapper tasks;
    @Autowired TicketMapper tickets;
    @Autowired AuthMapper auth;
    @Autowired AccountMailDispatchService disabledWorker;
    Browser admin, employee, engineer, stranger;
    UUID employeeId, engineerId;
    final String password = "TestPassword1234";
    record Browser(HttpClient client, String csrf) {}

    @BeforeEach
    void setUp() throws Exception {
        clock.now = Instant.parse("2026-10-19T00:00:00Z");
        db.execute("TRUNCATE notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        bootstrap.initialize("M06 Admin", "m06-admin@example.com", password);
        employeeId = active("m06-employee@example.com", "EMPLOYEE");
        engineerId = active("m06-engineer@example.com", "ENGINEER");
        active("m06-stranger@example.com", "EMPLOYEE");
        admin = signed("m06-admin@example.com"); employee = signed("m06-employee@example.com");
        engineer = signed("m06-engineer@example.com"); stranger = signed("m06-stranger@example.com");
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
        var created = call(employee, "POST", "/api/v1/tickets", Map.of("title", "M06 ticket", "description", "Communication",
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



    void change(Browser actor, UUID id, String action, Object body) throws Exception {
        var result = post(actor, id, action, body, version(id));
        assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
    }
    List<UUID> recipients(UUID id, String action) {
        return db.queryForList("SELECT recipient_id FROM notification_task WHERE ticket_id=? AND ticket_action=?", UUID.class, id, action);
    }
    AccountMailDispatchServiceImpl worker(SmtpMailService smtp, boolean enabled, NotificationMapper mapper) {
        var helper = new AccountMailSmtpTest();
        var config = helper.config(true, "sender@example.com", "http://localhost:5173", helper.localProperties(2525));
        return new AccountMailDispatchServiceImpl(mapper, users, auth, smtp, config, clock, transactions, tickets);
    }

    @Test
    void selectedActionsNotifyOnlyRequesterAndNewOrCurrentAssignee() throws Exception {
        UUID id = ticket();
        assertThat(count("notification_task")).isZero();
        change(admin, id, "assignments", Map.of("assigneeId", engineerId));
        assertThat(recipients(id, "assignments")).containsExactlyInAnyOrder(employeeId, engineerId);
        change(engineer, id, "claim", null);
        change(engineer, id, "internal-notes", Map.of("content", "never emailed"));
        change(engineer, id, "suspensions", Map.of("reason", "private reason"));
        change(engineer, id, "resume", null);
        change(admin, id, "priority-changes", Map.of("priority", "P1", "reason", "private priority reason"));
        assertThat(count("notification_task")).isEqualTo(2);
        change(employee, id, "replies", Map.of("content", "requester public reply"));
        assertThat(recipients(id, "replies")).containsExactly(engineerId);
        change(engineer, id, "resolutions", Map.of("summary", "private solution text"));
        assertThat(recipients(id, "resolutions")).containsExactly(employeeId);
        change(employee, id, "close", null);
        assertThat(recipients(id, "close")).containsExactly(engineerId);
        change(employee, id, "reopen", Map.of("reason", "private reopen reason"));
        assertThat(recipients(id, "reopen")).containsExactly(engineerId);
        UUID next = active("m06-next@example.com", "ENGINEER");
        change(engineer, id, "transfers", Map.of("assigneeId", next));
        assertThat(recipients(id, "transfers")).containsExactlyInAnyOrder(employeeId, next);
        assertThat(db.queryForList("SELECT DISTINCT template FROM notification_task", String.class)).containsExactly("TICKET");
    }

    @Test
    void recipientsAreDeduplicatedAndInactiveAccountsAreExcluded() throws Exception {
        UUID id = ticket();
        // A staff requester can also be the assignee; one event must still create only one task.
        db.update("UPDATE ticket SET requester_id=?,assignee_id=? WHERE id=?", engineerId, engineerId, id);
        change(admin, id, "replies", Map.of("content", "same recipient in both roles"));
        assertThat(recipients(id, "replies")).containsExactly(engineerId);
        db.update("UPDATE app_user SET status='DISABLED' WHERE id=?", engineerId);
        change(admin, id, "replies", Map.of("content", "inactive recipients excluded"));
        assertThat(recipients(id, "replies")).hasSize(1);
    }

    @Test
    void replayConflictConcurrentWritesAndQueueFailureDoNotDuplicateOrPartiallyCommit() throws Exception {
        var body = Map.of("content", "original public text");
        UUID rollback = ticket();
        long beforeAudit = count("audit_event");
        db.execute("ALTER TABLE notification_task ADD CONSTRAINT m06_queue_reject CHECK (ticket_id IS DISTINCT FROM '" + rollback + "'::uuid)");
        try {
            assertThat(post(engineer, rollback, "replies", body, 0).statusCode()).isGreaterThanOrEqualTo(500);
            assertThat(version(rollback)).isZero();
            assertThat(tickets.selectById(rollback).firstRespondedAt).isNull();
            assertThat(recipients(rollback, "replies")).isEmpty();
            assertThat(db.queryForObject("SELECT count(*) FROM ticket_reply WHERE ticket_id=?", Long.class, rollback)).isZero();
            assertThat(count("audit_event")).isEqualTo(beforeAudit);
        } finally { db.execute("ALTER TABLE notification_task DROP CONSTRAINT m06_queue_reject"); }
        error(post(stranger, rollback, "replies", body, 0), 404, "RESOURCE_NOT_FOUND");
        assertThat(recipients(rollback, "replies")).isEmpty();
    }



    @Test
    void realLocalSmtpReceivesMinimalMimeWithoutSecretsOrAccountKeyDependency() throws Exception {
        UUID id = ticket();
        db.update("UPDATE ticket SET title='SECRET_TITLE',description='SECRET_DESCRIPTION' WHERE id=?", id);
        change(engineer, id, "replies", Map.of("content", "SECRET_REPLY"));
        try (var capture = new AccountMailSmtpTest.SmtpCapture()) {
            var helper = new AccountMailSmtpTest();
            var config = helper.config(true, "sender@example.com", "http://localhost:5173/", helper.localProperties(capture.port()));
            var dispatch = new AccountMailDispatchServiceImpl(tasks, users, auth,
                new SmtpMailServiceImpl(config.javaMailSender(), config), config, clock, transactions, tickets);
            assertThat(dispatch.dispatchPending()).isEqualTo(1);
            var message = capture.message(); String content = (String) message.getContent();
            assertThat(message.getAllRecipients()).hasSize(1);
            assertThat(message.getAllRecipients()[0].toString()).isEqualTo("m06-employee@example.com");
            assertThat(message.getSubject()).contains(tickets.selectById(id).number, "新增公开回复");
            assertThat(content).contains("http://localhost:5173/tickets/" + id, "请登录")
                .doesNotContain("SECRET_TITLE", "SECRET_DESCRIPTION", "SECRET_REPLY", "token=", "内部");
            assertThat(message.getHeader("Auto-Submitted", null)).isEqualTo("auto-generated");
        }
        assertThat(db.queryForObject("SELECT status FROM notification_task WHERE ticket_id=?", String.class, id)).isEqualTo("SUCCESS");
    }

    @Test
    void sendTimeChecksRejectDisabledOrNoLongerVisibleRecipients() throws Exception {
        UUID disabled = ticket(); change(engineer, disabled, "replies", Map.of("content", "recipient disabled later"));
        db.update("UPDATE app_user SET status='DISABLED' WHERE id=?", employeeId);
        var smtp = mock(SmtpMailService.class);
        assertThat(worker(smtp, true, tasks).dispatchPending()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT error_code FROM notification_task WHERE ticket_id=?", String.class, disabled)).isEqualTo("ACCOUNT_UNAVAILABLE");
        db.update("UPDATE app_user SET status='ACTIVE' WHERE id=?", employeeId);
        UUID hidden = ticket();
        change(admin, hidden, "assignments", Map.of("assigneeId", engineerId));
        db.update("UPDATE app_user SET role='EMPLOYEE' WHERE id=?", engineerId);
        assertThat(worker(smtp, true, tasks).dispatchPending()).isEqualTo(2);
        assertThat(db.queryForObject("SELECT error_code FROM notification_task WHERE ticket_id=? AND recipient_id=?", String.class, hidden, engineerId)).isEqualTo("TICKET_NOT_VISIBLE");
        verify(smtp, times(1)).sendTicket(eq("m06-employee@example.com"), eq(hidden), anyString(), eq("assignments"));
        verifyNoMoreInteractions(smtp);
    }

    @Test
    void retryLeaseRecoveryConcurrencyAndUncertainCommitUseExistingFencing() throws Exception {
        UUID id = ticket(); change(engineer, id, "replies", Map.of("content", "retry"));
        var smtp = mock(SmtpMailService.class);
        doThrow(new com.openitsm.common.exception.BusinessException(503, "DEPENDENCY_UNAVAILABLE", "test only"))
            .when(smtp).sendTicket(anyString(), any(UUID.class), anyString(), anyString());
        var dispatch = worker(smtp, true, tasks);
        assertThat(dispatch.dispatchPending()).isEqualTo(1);
        assertThat(dispatch.dispatchPending()).isZero();
        for (long delay : new long[] {60, 300, 900}) { clock.advance(delay); assertThat(dispatch.dispatchPending()).isEqualTo(1); }
        assertThat(db.queryForObject("SELECT status FROM notification_task WHERE ticket_id=?", String.class, id)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT attempts FROM notification_task WHERE ticket_id=?", Integer.class, id)).isEqualTo(4);
        reset(smtp);
        clock.now = clock.instant().plusSeconds(60); engineer = signed("m06-engineer@example.com"); employee = signed("m06-employee@example.com");
        UUID uncertain = ticket(); change(engineer, uncertain, "replies", Map.of("content", "uncertain commit"));
        var broken = mock(NotificationMapper.class, org.mockito.AdditionalAnswers.delegatesTo(tasks));
        doThrow(new IllegalStateException("test write failure")).when(broken).finish(any(UUID.class), anyInt(), anyString(), nullable(String.class), any(Instant.class));
        assertThatThrownBy(() -> worker(smtp, true, broken).dispatchPending()).isInstanceOf(IllegalStateException.class);
        assertThat(db.queryForObject("SELECT status FROM notification_task WHERE ticket_id=?", String.class, uncertain)).isEqualTo("RUNNING");
        clock.advance(121);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var one = pool.submit(() -> { start.await(); return dispatch.dispatchPending(); });
            var two = pool.submit(() -> { start.await(); return dispatch.dispatchPending(); });
            start.countDown(); assertThat(one.get() + two.get()).isEqualTo(1);
        }
        verify(smtp, times(2)).sendTicket(anyString(), eq(uncertain), anyString(), eq("replies"));
        assertThat(db.queryForObject("SELECT status FROM notification_task WHERE ticket_id=?", String.class, uncertain)).isEqualTo("SUCCESS");
        UUID finalAttempt = UUID.randomUUID();
        tasks.insertTicketTask(finalAttempt, UUID.randomUUID(), employeeId, clock.instant(), uncertain, "replies");
        db.update("UPDATE notification_task SET attempts=3 WHERE id=?", finalAttempt);
        var old = tasks.claim(clock.instant(), clock.instant().plusSeconds(120), 4);
        clock.advance(121); assertThat(dispatch.dispatchPending()).isZero();
        assertThat(tasks.finish(old.id(), old.attempts(), "SUCCESS", null, clock.instant())).isZero();
        assertThat(db.queryForObject("SELECT status FROM notification_task WHERE id=?", String.class, finalAttempt)).isEqualTo("TIMEOUT");
    }

    @Test
    void v7UpgradePreservesEncryptedAccountTasks() {
        String schema = "m06_upgrade_" + UUID.randomUUID().toString().replace("-", "");
        var config = org.flywaydb.core.Flyway.configure().dataSource(db.getDataSource()).schemas(schema).defaultSchema(schema);
        config.target("7").load().migrate();
        UUID user = UUID.randomUUID(), task = UUID.randomUUID();
        db.update("INSERT INTO " + schema + ".app_user(id,email_normalized,name,role,status) VALUES (?,'upgrade@example.com','Upgrade','EMPLOYEE','INVITED')", user);
        db.update("INSERT INTO " + schema + ".notification_task(id,event_id,recipient_id,template,status,next_attempt_at,sealed_payload,token_generation) VALUES (?,?,?,'INVITE','PENDING',CURRENT_TIMESTAMP,'retained-encrypted-payload',1)", task, task, user);
        var before = db.queryForMap("SELECT * FROM " + schema + ".notification_task WHERE id=?", task);
        assertThat(config.target("8").load().migrate().migrationsExecuted).isEqualTo(1);
        assertThat(db.queryForMap("SELECT * FROM " + schema + ".notification_task WHERE id=?", task)).containsAllEntriesOf(before);
        assertThat(config.target("8").load().migrate().migrationsExecuted).isZero();
    }

    @Test
    void memberLocksPermitQueueForeignKeysWhileStillSerializingMemberChanges() throws Exception {
        UUID id = ticket(); change(admin, id, "assignments", Map.of("assigneeId", engineerId));
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var holding = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                users.lock(engineerId); locked.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var competingMutation = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> users.lock(engineerId)));
            var reply = pool.submit(() -> post(employee, id, "replies", Map.of("content", "FK lock safety"), 1));
            try {
                assertThat(reply.get(3, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
                assertThatThrownBy(() -> competingMutation.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally { release.countDown(); }
            holding.get(5, TimeUnit.SECONDS); competingMutation.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(recipients(id, "replies")).containsExactly(engineerId);
    }

    @AfterAll
    void verifyPackagedApplicationWithScheduledLocalSmtp() throws Exception {
        if (!"true".equals(System.getenv("OPENITSM_M06_PACKAGED_VERIFY"))) return;
        if (System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL") != null)
            throw new IllegalStateException("Packaged verification requires the isolated Testcontainers database");
        setUp();
        int originalPort = port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        Path log = Path.of("target/m06-packaged-app.log").toAbsolutePath();
        try (var capture = new AccountMailSmtpTest.SmtpCapture()) {
            var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-jar",
                "target/backend-0.1.0-SNAPSHOT.jar", "--spring.profiles.active=local", "--server.port=" + port,
                "--spring.datasource.url=" + M01IntegrationTest.PG.getJdbcUrl(),
                "--spring.datasource.username=" + M01IntegrationTest.PG.getUsername(),
                "--spring.datasource.password=" + M01IntegrationTest.PG.getPassword(),
                "--openitsm.account-mail.from=sender@example.com", "--openitsm.account-mail.public-base-url=http://localhost:5173",
                "--spring.mail.host=127.0.0.1", "--spring.mail.port=" + capture.port(),
                "--spring.mail.username=", "--spring.mail.password=", "--spring.mail.properties.mail.smtp.auth=false",
                "--spring.mail.properties.mail.smtp.ssl.enable=false", "--spring.mail.properties.mail.smtp.starttls.enable=false",
                "--spring.mail.properties.mail.smtp.starttls.required=false", "--spring.mail.ssl.enabled=false",
                "--openitsm.cookie-secure=false", "--openitsm.cookie-name=openitsm-test-session",
                "--openitsm.origin=http://localhost:5173", "--openitsm.attachments.root=" + Path.of("target/m06-attachments").toAbsolutePath())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            try {
                boolean ready = false; var probe = new Browser(HttpClient.newHttpClient(), null);
                for (int i = 0; i < 160 && process.isAlive(); i++) {
                    try { ready = call(probe, "GET", "/api/v1/auth/csrf", null, null, null).statusCode() == 200; }
                    catch (java.io.IOException ignored) { }
                    if (ready) break;
                    Thread.sleep(250);
                }
                assertThat(ready).as("Packaged application readiness; see " + log).isTrue();
                assertThat(call(probe, "GET", "/api/v1/auth/csrf", null, null, null).statusCode()).isEqualTo(200);
                employee = signed("m06-employee@example.com"); engineer = signed("m06-engineer@example.com");
                UUID id = ticket();
                change(engineer, id, "claim", null);
                var body = Map.of("content", "packaged private body marker"); String key = UUID.randomUUID().toString();
                ok(call(engineer, "POST", path(id, "replies"), body, key, "\"1\""), 2);
                ok(call(engineer, "POST", path(id, "replies"), body, key, "\"1\""), 2);
                var message = capture.message();
                assertThat(message.getAllRecipients()[0].toString()).isEqualTo("m06-employee@example.com");
                assertThat((String) message.getContent()).contains("http://localhost:5173/tickets/" + id).doesNotContain("packaged private body marker");
                String status = null;
                for (int i = 0; i < 100; i++) {
                    status = db.queryForObject("SELECT status FROM notification_task WHERE ticket_id=?", String.class, id);
                    if ("SUCCESS".equals(status)) break;
                    Thread.sleep(50);
                }
                assertThat(status).isEqualTo("SUCCESS");
                assertThat(db.queryForObject("SELECT attempts FROM notification_task WHERE ticket_id=?", Integer.class, id)).isEqualTo(1);
                assertThat(version(id)).isEqualTo(2);
                System.out.println("M06_PACKAGED_VERIFIED: live/ready=UP, V8, real HTTP, scheduled local SMTP, one task, attempts=1, SUCCESS, version=2, replay preserved");
            } finally {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
                port = originalPort;
            }
        }
    }
}
