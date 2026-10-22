package com.openitsm;

import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies OpenItsmApplication boots completely on an ephemeral HTTP port against an isolated PostgreSQL
 * database: Spring context, MyBatis Plus configuration, Flyway migrations and the embedded Tomcat.
 * It never touches the application database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ApplicationStartupTest {
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.openitsm.config.AccountMailSchedulingConfig mailScheduler;

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
            String testDatabase = System.getenv("OPENITSM_TEST_STARTUP_DATABASE");
            if (testDatabase == null || testDatabase.isBlank()) {
                testDatabase = "openitsm_startup_test_" + UUID.randomUUID().toString().replace("-", "");
                try (var connection = DriverManager.getConnection(adminUrl, user, password); var sql = connection.createStatement()) {
                    sql.execute("CREATE DATABASE " + testDatabase);
                } catch (Exception ex) { throw new IllegalStateException("Cannot create isolated startup test database", ex); }
            } else if (!testDatabase.matches("openitsm_startup_test_[a-f0-9]{32}")) {
                throw new IllegalStateException("Only an explicitly named isolated startup test database may be reused");
            }
            String testUrl = adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + testDatabase;
            props.add("spring.datasource.url", () -> testUrl);
            props.add("spring.datasource.username", () -> user);
            props.add("spring.datasource.password", () -> password);
            System.out.println("Isolated startup test database retained: " + testDatabase);
        }
        props.add("openitsm.cookie-secure", () -> false);
        props.add("openitsm.cookie-name", () -> "openitsm-startup-test-session");
        props.add("openitsm.origin", () -> "http://localhost:5173");
    }

    @Autowired TestRestTemplate rest;
    @LocalServerPort int port;

    @Test void applicationStartsAndServesHealthOnAnEphemeralPort() {
        assertThat(port).isGreaterThan(0);

        ResponseEntity<String> live = rest.getForEntity("/api/v1/auth/csrf", String.class);
        assertThat(live.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(live.getBody()).contains("csrfToken");

        // Readiness proves the migrated PostgreSQL schema is reachable from the started application.
        ResponseEntity<String> ready = rest.getForEntity("/api/v1/auth/csrf", String.class);
        assertThat(ready.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ready.getBody()).contains("csrfToken");
    }

    @Test void anonymousApiRequestsAreRejectedWithTheErrorEnvelope() {
        ResponseEntity<String> me = rest.getForEntity("/api/v1/auth/me", String.class);
        assertThat(me.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(me.getBody()).contains("SESSION_EXPIRED").contains("requestId");
    }
}
