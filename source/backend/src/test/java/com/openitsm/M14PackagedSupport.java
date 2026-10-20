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

/** Optional independent JAR -> real Python -> local model peer verification and browser fixture. */
final class M14PackagedSupport {
    static void verify(M14HttpTest test) throws Exception {
        if (System.getenv("OPENITSM_TEST_ADMIN_JDBC_URL") != null) throw new IllegalStateException("Packaged verification requires Testcontainers");
        var provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var calls = new AtomicInteger();
        provider.createContext("/v1/chat/completions", exchange -> {
            var request = test.json.readTree(exchange.getRequestBody());
            boolean fixed = request.path("messages").size() == 1 && request.path("messages").get(0).path("content").asText()
                .equals("Reply with OK. This is an OpenITSM connection test.");
            calls.incrementAndGet();
            byte[] bytes = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(fixed ? 200 : 400, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        provider.start();
        int pythonPort = freePort(), appPort = freePort();
        var pythonDir = Path.of("../ai-service").toAbsolutePath().normalize();
        var python = new ProcessBuilder(pythonDir.resolve(".venv/Scripts/python.exe").toString(), "-m", "uvicorn", "app.main:app",
            "--host", "127.0.0.1", "--port", String.valueOf(pythonPort), "--no-access-log").directory(pythonDir.toFile())
            .redirectErrorStream(true).redirectOutput(Path.of("target/m14-python-app.log").toFile());
        python.environment().put("OPENITSM_AI_INTERNAL_TOKEN", M14HttpTest.TOKEN);
        python.environment().put("OPENITSM_AI_ALLOW_LOCAL_MODEL", "true");
        var app = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin/java.exe").toString(), "-jar", "target/backend-0.1.0-SNAPSHOT.jar",
            "--spring.profiles.active=local", "--spring.config.import=", "--server.port=" + appPort, "--openitsm.origin=http://localhost:5189",
            "--openitsm.account-mail.enabled=false", "--openitsm.ticket-mail.enabled=false", "--spring.mail.host=", "--spring.mail.username=", "--spring.mail.password=",
            "--openitsm.account-mail.from=", "--openitsm.model.api-key=", "--openitsm.model.base-url=", "--openitsm.model.name=",
            "--openitsm.ai.service-url=http://127.0.0.1:" + pythonPort, "--openitsm.cookie-name=openitsm-test-session",
            "--openitsm.attachments.root=" + Path.of("target/m14-packaged-files").toAbsolutePath(),
            "--openitsm.account-mail-key-file=" + M01IntegrationTest.KEY_FILE)
            .redirectErrorStream(true).redirectOutput(Path.of("target/m14-packaged-app.log").toFile());
        app.environment().put("OPENITSM_DB_URL", M01IntegrationTest.PG.getJdbcUrl());
        app.environment().put("OPENITSM_DB_USER", M01IntegrationTest.PG.getUsername());
        app.environment().put("OPENITSM_DB_PASSWORD", M01IntegrationTest.PG.getPassword());
        app.environment().put("OPENITSM_AI_INTERNAL_TOKEN", M14HttpTest.TOKEN);
        Process pythonProcess = null, appProcess = null;
        int oldPort = test.port;
        String oldOrigin = test.origin;
        try {
            pythonProcess = python.start(); appProcess = app.start();
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            boolean ready = false;
            for (int i = 0; i < 90; i++) {
                if (!pythonProcess.isAlive() || !appProcess.isAlive()) throw new IllegalStateException("Fixture exited; inspect m14 packaged logs");
                try {
                    var result = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + appPort + "/health/ready")).timeout(Duration.ofSeconds(2)).build(), HttpResponse.BodyHandlers.ofString());
                    if (result.statusCode() == 200) { ready = true; break; }
                } catch (Exception ignored) {}
                Thread.sleep(500);
            }
            assertThat(ready).isTrue(); test.port = appPort; test.origin = "http://localhost:5189";
            test.admin = test.signed("m14-admin@example.com");
            var saved = test.patch(Map.of("teamName", "M14 Browser Team", "model", Map.of("baseUrl", "http://127.0.0.1:" + provider.getAddress().getPort() + "/v1", "model", "local-test-model", "apiKey", "m14-local-provider-test-key")), 0);
            assertThat(saved.statusCode()).as(saved.body()).isEqualTo(200);
            var result = test.test();
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);
            assertThat(test.data(result).path("testedVersion").asInt()).isEqualTo(1);
            assertThat(calls).hasValue(1); assertThat(test.runtime.current().aiEnabled()).isFalse();
            test.employee = test.signed("employee@example.com");
            test.error(test.call(test.employee, "GET", "/api/v1/settings", null, null, null), 403, "FORBIDDEN");
            assertThat(test.call(test.admin, "GET", "/api/v1/tickets?view=MINE", null, null, null).statusCode()).isEqualTo(200);
            Files.writeString(Path.of("target/m14-fixture.json"), test.json.writeValueAsString(Map.of("backendUrl", "http://127.0.0.1:" + appPort, "pythonPort", pythonPort)));
            System.out.println("M14_PACKAGED_VERIFIED backend=http://127.0.0.1:" + appPort + " python=" + pythonPort + " fixedModelCalls=" + calls.get());
            int hold = Integer.parseInt(System.getenv().getOrDefault("OPENITSM_M14_HOLD_SECONDS", "0"));
            String release = System.getenv("OPENITSM_M14_RELEASE_FILE");
            for (int i = 0; i < Math.min(hold, 1200); i++) {
                if (release != null && Files.exists(Path.of(release))) break;
                Thread.sleep(1000);
            }
            assertThat(calls.get()).isGreaterThanOrEqualTo(1);
        } finally {
            test.port = oldPort; test.origin = oldOrigin;
            if (appProcess != null) { appProcess.destroy(); if (!appProcess.waitFor(10, TimeUnit.SECONDS)) appProcess.destroyForcibly(); }
            if (pythonProcess != null) { pythonProcess.destroy(); if (!pythonProcess.waitFor(5, TimeUnit.SECONDS)) pythonProcess.destroyForcibly(); }
            provider.stop(0);
        }
    }
    static int freePort() throws Exception { try (var socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { return socket.getLocalPort(); } }
}
