package com.openitsm;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.config.AccountMailConfig;
import com.openitsm.entity.UserEntity;
import com.openitsm.mapper.NotificationMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.mapper.AuthMapper;
import com.openitsm.service.AccountMailDispatchService;
import com.openitsm.service.AccountTokenService;
import com.openitsm.service.AuthService;
import com.openitsm.service.SmtpMailService;
import com.openitsm.service.impl.AccountMailCipher;
import com.openitsm.service.impl.AccountMailDispatchServiceImpl;
import com.openitsm.service.impl.SmtpMailServiceImpl;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import java.sql.DriverManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@Import(M01IntegrationTest.TimeConfig.class)
class AccountMailDispatchTest {
    static { System.setProperty("api.version", "1.44"); }
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:17.11");
    @DynamicPropertySource static void properties(DynamicPropertyRegistry props) {
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
            String database = "openitsm_m01_test_" + UUID.randomUUID().toString().replace("-", "");
            try (var connection = DriverManager.getConnection(adminUrl, user, password); var sql = connection.createStatement()) {
                sql.execute("CREATE DATABASE " + database);
            } catch (Exception ex) { throw new IllegalStateException("Cannot create isolated mail test database", ex); }
            props.add("spring.datasource.url", () -> adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database);
            props.add("spring.datasource.username", () -> user);
            props.add("spring.datasource.password", () -> password);
        }
        props.add("openitsm.account-mail-key-file", M01IntegrationTest.KEY_FILE::toString);
        props.add("openitsm.account-mail.enabled", () -> true);
        props.add("openitsm.account-mail.from", () -> "sender@example.com");
        props.add("openitsm.account-mail.public-base-url", () -> "http://localhost:5173");
        props.add("openitsm.account-mail.initial-delay-ms", () -> 3600000);
        props.add("spring.mail.host", () -> "127.0.0.1");
        props.add("spring.mail.port", () -> 2525);
        props.add("spring.mail.properties.mail.smtp.auth", () -> false);
        props.add("spring.mail.properties.mail.smtp.ssl.enable", () -> false);
    }
    @Autowired AccountMailDispatchService delivery;
    @Autowired AccountTokenService tokens;
    @Autowired AuthService authService;
    @Autowired NotificationMapper tasks;
    @Autowired UserMapper users;
    @Autowired AccountMailCipher cipher;
    @Autowired AccountMailConfig mailConfig;
    @Autowired AuthMapper authMapper;
    @Autowired JdbcTemplate db;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired M01IntegrationTest.TestClock clock;
    @MockitoBean SmtpMailService smtp;

    @BeforeEach void resetDatabase() {
        clock.now = Instant.parse("2026-10-15T02:00:00Z");
        // Same explicitly isolated container/test database convention as M01IntegrationTest.
        db.execute("TRUNCATE idempotency_record,notification_task,audit_event,account_token,auth_session,auth_rate_bucket,account_bootstrap,app_user CASCADE");
        reset(smtp);
    }
    UUID issue(String purpose) {
        var user = new UserEntity();
        user.id = UUID.randomUUID(); user.name = "Mail Test"; user.emailNormalized = user.id + "@example.com";
        user.role = "EMPLOYEE"; user.status = purpose.equals("INVITE") ? "INVITED" : "ACTIVE";
        user.passwordHash = purpose.equals("RESET") ? "test-only-hash" : null; user.updatedAt = clock.instant();
        users.insert(user);
        issueAgain(user.id, purpose);
        return user.id;
    }
    void issueAgain(UUID user, String purpose) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            users.lock(user);
            tokens.issue(user, purpose, purpose.equals("INVITE") ? Duration.ofHours(48) : Duration.ofMinutes(30));
        });
    }
    String status(UUID user) { return db.queryForObject("SELECT status FROM notification_task WHERE recipient_id=? ORDER BY token_generation DESC LIMIT 1", String.class, user); }

    @Test void decryptedInvitationAndResetReachRealSmtp() throws Exception {
        for (String purpose : new String[] { "INVITE", "RESET" }) {
            UUID user = issue(purpose);
            try (var capture = new AccountMailSmtpTest.SmtpCapture()) {
                var helper = new AccountMailSmtpTest();
                var config = helper.config(true, "sender@example.com", "http://localhost:5173", helper.localProperties(capture.port()));
                var realSender = new SmtpMailServiceImpl(config.javaMailSender(), config);
                doAnswer(call -> { realSender.send(call.getArgument(0), call.getArgument(1), call.getArgument(2)); return null; })
                    .when(smtp).send(anyString(), eq(purpose), anyString());
                assertThat(delivery.dispatchPending()).isEqualTo(1);
                assertThat(status(user)).isEqualTo("SUCCESS");
                String body = (String) capture.message().getContent();
                assertThat(body).contains(purpose.equals("INVITE") ? "/activate?token=" : "/reset-password?token=");
            }
        }
    }

    @Test void revokedExpiredAndDisabledAccountsAreNeverSent() {
        UUID revoked = issue("INVITE");
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> { users.lock(revoked); tokens.invalidate(revoked, "INVITE"); });
        UUID expired = issue("RESET");
        UUID disabled = issue("INVITE");
        db.update("UPDATE app_user SET status='DISABLED' WHERE id=?", disabled);
        clock.advance(1801);
        assertThat(delivery.dispatchPending()).isEqualTo(2);
        assertThat(status(revoked)).isEqualTo("FAILED");
        assertThat(status(expired)).isEqualTo("FAILED");
        assertThat(status(disabled)).isEqualTo("FAILED");
        verifyNoInteractions(smtp);
    }

    @Test void resendingCancelsOldGenerationAndDeliversOnlyCurrentToken() {
        UUID user = issue("INVITE");
        issueAgain(user, "INVITE");
        assertThat(delivery.dispatchPending()).isEqualTo(1);
        verify(smtp, times(1)).send(eq(user + "@example.com"), eq("INVITE"), anyString());
        assertThat(db.queryForList("SELECT status FROM notification_task WHERE recipient_id=? ORDER BY token_generation", String.class, user))
            .containsExactly("FAILED", "SUCCESS");
    }

    @Test void failuresBackOffWithSameTokenAndStopAfterFourAttempts() {
        UUID user = issue("INVITE");
        doThrow(new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "test failure")).when(smtp).send(anyString(), anyString(), anyString());
        assertThat(delivery.dispatchPending()).isEqualTo(1);
        assertThat(status(user)).isEqualTo("PENDING");
        assertThat(delivery.dispatchPending()).isZero();
        for (long delay : new long[] { 60, 300, 900 }) { clock.advance(delay); assertThat(delivery.dispatchPending()).isEqualTo(1); }
        assertThat(status(user)).isEqualTo("FAILED");
        assertThat(db.queryForObject("SELECT attempts FROM notification_task WHERE recipient_id=?", Integer.class, user)).isEqualTo(4);
        var arguments = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(smtp, times(4)).send(anyString(), eq("INVITE"), arguments.capture());
        assertThat(arguments.getAllValues()).containsOnly(arguments.getAllValues().get(0));
        clock.advance(10000);
        assertThat(delivery.dispatchPending()).isZero();
    }

    @Test void concurrentWorkersClaimOnce() throws Exception {
        UUID user = issue("INVITE");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            Callable<Integer> call = () -> { start.await(); return delivery.dispatchPending(); };
            var first = pool.submit(call); var second = pool.submit(call); start.countDown();
            assertThat(first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(status(user)).isEqualTo("SUCCESS");
        verify(smtp, times(1)).send(anyString(), anyString(), anyString());
    }

    @Test void expiredLeaseIsReclaimedAndOldAttemptCannotOverwriteNewOwner() {
        UUID user = issue("INVITE");
        var old = tasks.claim(clock.instant(), clock.instant().plusSeconds(120), 4);
        assertThat(old).isNotNull();
        clock.advance(121);
        assertThat(delivery.dispatchPending()).isEqualTo(1);
        assertThat(status(user)).isEqualTo("SUCCESS");
        assertThat(tasks.finish(old.id(), old.attempts(), "FAILED", "OLD_ATTEMPT", clock.instant())).isZero();
        assertThat(tasks.lockClaim(old.id(), old.attempts(), clock.instant())).isNull();
    }

    @Test void crashedFinalAttemptBecomesTimeoutRatherThanRemainingRunningForever() {
        UUID user = issue("INVITE");
        db.update("UPDATE notification_task SET attempts=3 WHERE recipient_id=?", user);
        assertThat(tasks.claim(clock.instant(), clock.instant().plusSeconds(120), 4).attempts()).isEqualTo(4);
        clock.advance(121);
        assertThat(delivery.dispatchPending()).isZero();
        assertThat(status(user)).isEqualTo("TIMEOUT");
        verifyNoInteractions(smtp);
    }

    @Test void tamperedPayloadOrWrongGenerationIsNotDelivered() {
        UUID tampered = issue("INVITE");
        UUID wrongGeneration = issue("INVITE");
        db.update("UPDATE notification_task SET sealed_payload='invalid' WHERE recipient_id=?", tampered);
        db.update("UPDATE notification_task SET token_generation=99 WHERE recipient_id=?", wrongGeneration);
        assertThat(delivery.dispatchPending()).isEqualTo(2);
        assertThat(status(tampered)).isEqualTo("FAILED");
        assertThat(status(wrongGeneration)).isEqualTo("FAILED");
        verifyNoInteractions(smtp);
    }

    @Test void activationCancelsQueuedInvitationAndUnknownTokenHashIsRejected() {
        for (String purpose : new String[] { "INVITE", "RESET" }) {
            UUID consumed = issue(purpose);
            var row = db.queryForMap("SELECT sealed_payload,token_generation FROM notification_task WHERE recipient_id=?", consumed);
            String raw = cipher.open((String) row.get("sealed_payload"), consumed + ":" + purpose + ":" + row.get("token_generation"));
            authService.consume(raw, "TestPassword1234", purpose, UUID.randomUUID());
            assertThat(status(consumed)).isEqualTo("FAILED");
        }
        UUID forged = issue("INVITE");
        String sealed = cipher.seal("b".repeat(43), forged + ":INVITE:1");
        db.update("UPDATE notification_task SET sealed_payload=? WHERE recipient_id=?", sealed, forged);
        assertThat(delivery.dispatchPending()).isEqualTo(1);
        assertThat(db.queryForObject("SELECT error_code FROM notification_task WHERE recipient_id=?", String.class, forged)).isEqualTo("TOKEN_INVALID");
        verifyNoInteractions(smtp);
    }

    @Test void databaseGenerationUsedFlagAndTokenOwnerAreIndependentlyChecked() {
        UUID generation = issue("INVITE");
        UUID used = issue("RESET");
        UUID wrongOwner = issue("INVITE");
        db.update("UPDATE account_token SET generation=2 WHERE user_id=?", generation);
        db.update("UPDATE account_token SET used_at=? WHERE user_id=?", java.sql.Timestamp.from(clock.instant()), used);
        db.update("UPDATE account_token SET user_id=?,generation=3 WHERE user_id=?", generation, wrongOwner);
        assertThat(delivery.dispatchPending()).isEqualTo(3);
        for (UUID user : new UUID[] { generation, used, wrongOwner }) {
            assertThat(status(user)).isEqualTo("FAILED");
            assertThat(db.queryForObject("SELECT error_code FROM notification_task WHERE recipient_id=?", String.class, user)).isEqualTo("TOKEN_INVALID");
        }
        verifyNoInteractions(smtp);
    }

    @Test void acceptedSmtpWithFailedDatabaseWriteIsRetriedWithTheSameLinkAfterLeaseExpiry() throws Exception {
        UUID user = issue("INVITE");
        doAnswer(call -> {
            try (var capture = new AccountMailSmtpTest.SmtpCapture()) {
                var helper = new AccountMailSmtpTest();
                var config = helper.config(true, "sender@example.com", "http://localhost:5173", helper.localProperties(capture.port()));
                new SmtpMailServiceImpl(config.javaMailSender(), config).send(call.getArgument(0), call.getArgument(1), call.getArgument(2));
                assertThat((String) capture.message().getContent()).contains(call.getArgument(2, String.class));
            }
            return null;
        }).when(smtp).send(anyString(), anyString(), anyString());
        var failingMapper = mock(NotificationMapper.class, org.mockito.AdditionalAnswers.delegatesTo(tasks));
        doThrow(new IllegalStateException("simulated database write failure")).when(failingMapper)
            .finish(any(UUID.class), anyInt(), anyString(), nullable(String.class), any(Instant.class));
        var uncertainWorker = new AccountMailDispatchServiceImpl(failingMapper, users, authMapper, cipher,
            smtp, mailConfig, clock, transactionManager);
        assertThatThrownBy(uncertainWorker::dispatchPending).isInstanceOf(IllegalStateException.class);
        assertThat(status(user)).isEqualTo("RUNNING");
        clock.advance(121);
        assertThat(delivery.dispatchPending()).isEqualTo(1);
        assertThat(status(user)).isEqualTo("SUCCESS");
        var arguments = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(smtp, times(2)).send(anyString(), eq("INVITE"), arguments.capture());
        assertThat(arguments.getAllValues()).containsOnly(arguments.getAllValues().get(0));
    }

    @Test void eachPollHasABoundedBatchSize() {
        for (int i = 0; i < 12; i++) issue("INVITE");
        assertThat(delivery.dispatchPending()).isEqualTo(10);
        assertThat(db.queryForObject("SELECT count(*) FROM notification_task WHERE status='PENDING'", Integer.class)).isEqualTo(2);
        assertThat(delivery.dispatchPending()).isEqualTo(2);
        verify(smtp, times(12)).send(anyString(), anyString(), anyString());
    }

    @Test void accountMutationWaitsForInFlightSendThenInvalidatesTheToken() throws Exception {
        UUID user = issue("INVITE");
        var sending = new CountDownLatch(1); var release = new CountDownLatch(1);
        doAnswer(call -> { sending.countDown(); if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test deadline"); return null; })
            .when(smtp).send(anyString(), anyString(), anyString());
        try (var pool = Executors.newFixedThreadPool(2)) {
            var send = pool.submit(delivery::dispatchPending);
            assertThat(sending.await(5, TimeUnit.SECONDS)).isTrue();
            var revoke = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> { users.lock(user); tokens.invalidate(user, "INVITE"); }));
            try { assertThatThrownBy(() -> revoke.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            assertThat(send.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            revoke.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(db.queryForObject("SELECT count(*) FROM account_token WHERE user_id=? AND used_at IS NULL", Integer.class, user)).isZero();
        assertThat(delivery.dispatchPending()).isZero();
    }
}
