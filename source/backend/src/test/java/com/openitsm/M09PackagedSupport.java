package com.openitsm;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

/** Optional independent JAR -> Python -> local model verification and browser fixture. */
final class M09PackagedSupport {
    private M09PackagedSupport() {}
    static void verify(M09HttpTest test) throws Exception {
        if (System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL") != null) throw new IllegalStateException("Packaged verification requires Testcontainers");
        test.db.update("UPDATE ticket_type SET description='Network and connectivity requests' WHERE id=?", test.newType);
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        var descriptionSeen = new java.util.concurrent.atomic.AtomicBoolean();
        provider.createContext("/v1/chat/completions", exchange -> {
            var request = test.json.readTree(exchange.getRequestBody());
            var materials = test.json.readTree(request.path("messages").get(1).path("content").asText());
            var type = test.newType.toString();
            for (var candidate : materials.path("businessTypes")) {
                if (candidate.path("id").asText().equals(type)
                    && candidate.path("description").asText().equals("Network and connectivity requests")) descriptionSeen.set(true);
            }
            if (!materials.path("businessTypes").toString().contains(type)) {
                exchange.sendResponseHeaders(400, -1); exchange.close(); return;
            }
            calls.incrementAndGet();
            String output = test.json.writeValueAsString(Map.of("businessTypeId", type, "reason", "Matches supplied issue", "suggestedPriority", "P2"));
            byte[] bytes = test.json.writeValueAsString(Map.of("choices", List.of(Map.of("message", Map.of("content", output)))))
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        provider.start();
        int pythonPort = freePort(), appPort = freePort();
        var pythonDir = Path.of("../ai-service").toAbsolutePath().normalize();
        var python = new ProcessBuilder(pythonDir.resolve(".venv/Scripts/python.exe").toString(), "-m", "uvicorn", "app.main:app",
            "--host", "127.0.0.1", "--port", String.valueOf(pythonPort), "--no-access-log").directory(pythonDir.toFile())
            .redirectErrorStream(true).redirectOutput(Path.of("target/m09-python-app.log").toFile());
        python.environment().put("OPENITSM_AI_INTERNAL_TOKEN", M09HttpTest.TOKEN);
        python.environment().put("OPENITSM_AI_ALLOW_LOCAL_MODEL", "true");
        var app = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-jar", "target/backend-0.1.0-SNAPSHOT.jar",
            "--spring.profiles.active=local", "--spring.config.import=", "--server.port=" + appPort, "--openitsm.origin=http://localhost:5189",
            "--openitsm.account-mail.enabled=false", "--openitsm.ticket-mail.enabled=false", "--spring.mail.host=", "--spring.mail.username=", "--spring.mail.password=",
            "--openitsm.account-mail.from=", "--openitsm.model.api-key=", "--openitsm.model.base-url=", "--openitsm.model.name=",
            "--openitsm.ai.service-url=http://127.0.0.1:" + pythonPort, "--openitsm.cookie-name=openitsm-m09-session",
            "--openitsm.attachments.root=" + Path.of("target/m09-packaged-files").toAbsolutePath(),
            "--openitsm.account-mail-key-file=" + M01IntegrationTest.KEY_FILE)
            .redirectErrorStream(true).redirectOutput(Path.of("target/m09-packaged-app.log").toFile());
        app.environment().put("OPENITSM_DB_URL", M01IntegrationTest.PG.getJdbcUrl());
        app.environment().put("OPENITSM_DB_USER", M01IntegrationTest.PG.getUsername());
        app.environment().put("OPENITSM_DB_PASSWORD", M01IntegrationTest.PG.getPassword());
        app.environment().put("OPENITSM_AI_INTERNAL_TOKEN", M09HttpTest.TOKEN);
        Process pythonProcess = null, appProcess = null;
        int oldPort = test.port;
        String oldOrigin = test.origin;
        try {
            pythonProcess = python.start(); appProcess = app.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            boolean ready = false;
            for (int i = 0; i < 90; i++) {
                if (!pythonProcess.isAlive() || !appProcess.isAlive()) throw new IllegalStateException("Fixture exited; inspect m09 packaged logs");
                try {
                    var result = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + "/health/ready"))
                        .timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    if (result.statusCode() == 200) { ready = true; break; }
                } catch (Exception ignored) {}
                Thread.sleep(500);
            }
            assertThat(ready).isTrue(); test.port = appPort; test.origin = "http://localhost:5189";
            test.admin = test.signed("m09-admin@example.com");
            test.employee = test.signed("m09-employee@example.com");
            var saved = test.call(test.admin, "PATCH", "/api/v1/settings", Map.of("aiEnabled", true,
                "model", Map.of("baseUrl", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1",
                    "model", "local-m09-test", "apiKey", "m09-local-provider-test-key")), UUID.randomUUID().toString(), "\"0\"");
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            var id = test.ticket("NEW");
            var result = test.suggest(test.employee, id);
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            assertThat(test.data(result).path("businessTypeId").asText()).isEqualTo(test.newType.toString());
            assertThat(test.data(result).path("suggestedPriority").asText()).isEqualTo("P2");
            assertThat(calls).hasValue(1);
            assertThat(descriptionSeen).as("Type description reaches the local model through Java and Python").isTrue();
            var manual = test.confirm(test.employee, id, Map.of("businessTypeId", test.newType), UUID.randomUUID().toString(), 0);
            assertThat(manual.statusCode()).as(manual.body()).isEqualTo(200);
            assertThat(test.db.queryForObject("SELECT priority FROM ticket WHERE id=?", String.class, id)).isEqualTo("P3");
            Files.writeString(Path.of("target/m09-fixture.json"), test.json.writeValueAsString(Map.of(
                "backendUrl", "http://127.0.0.1:" + appPort, "pythonPort", pythonPort, "ticketId", id.toString(),
                "adminEmail", "m09-admin@example.com", "employeeEmail", "m09-employee@example.com")));
            System.out.println("M09_PACKAGED_VERIFIED backend=http://127.0.0.1:" + appPort + " python=" + pythonPort + " modelCalls=" + calls.get());
            int hold = Integer.parseInt(System.getenv().getOrDefault("OPENITSM_M09_HOLD_SECONDS", "0"));
            String release = System.getenv("OPENITSM_M09_RELEASE_FILE");
            for (int i = 0; i < Math.min(hold, 1200); i++) {
                if (release != null && Files.exists(Path.of(release))) break;
                Thread.sleep(1000);
            }
        } finally {
            test.port = oldPort; test.origin = oldOrigin;
            if (appProcess != null) { appProcess.destroy(); if (!appProcess.waitFor(10, TimeUnit.SECONDS)) appProcess.destroyForcibly(); }
            if (pythonProcess != null) { pythonProcess.destroy(); if (!pythonProcess.waitFor(5, TimeUnit.SECONDS)) pythonProcess.destroyForcibly(); }
            provider.stop(0);
        }
    }
    static int freePort() throws Exception { try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
}
