package com.openitsm.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.service.SettingsSnapshot;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class PythonAiClient {
    private final ObjectMapper json;
    private final Environment env;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    public PythonAiClient(ObjectMapper json, Environment env) { this.json = json; this.env = env; }

    public void testConnection(SettingsSnapshot.Model model) {
        String token = env.getProperty("openitsm.ai.internal-token", env.getProperty("OPENITSM_AI_INTERNAL_TOKEN", ""));
        String base = env.getProperty("openitsm.ai.service-url", env.getProperty("OPENITSM_AI_SERVICE_URL", "http://127.0.0.1:8001"));
        if (token.length() < 32 || token.contains("\r") || token.contains("\n")) throw unavailable();
        CompletableFuture<HttpResponse<byte[]>> task = null;
        try {
            URI target = URI.create(base.replaceAll("/+$", "") + "/internal/v1/model-connection-tests");
            if (target.getHost() == null || target.getUserInfo() != null || target.getQuery() != null || target.getFragment() != null
                || !("https".equals(target.getScheme()) || ("http".equals(target.getScheme())
                    && com.openitsm.service.impl.SettingsValidation.loopback(target.getHost())))) throw unavailable();
            String body = json.writeValueAsString(Map.of("modelConnection", Map.of("baseUrl", model.baseUrl(), "model", model.model(), "apiKey", model.apiKey())));
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.ofString(body)).build();
            task = http.sendAsync(request, info -> new BoundedBodySubscriber());
            var response = task.get(16, TimeUnit.SECONDS);
            var result = json.readTree(response.body());
            if (response.statusCode() == 200) {
                if (result != null && result.isObject() && result.size() == 1 && "SUCCESS".equals(result.path("status").asText())) return;
                throw invalid();
            }
            if (result == null) throw invalid();
            String code = result.path("code").asText();
            if (response.statusCode() == 504 && "AI_TIMEOUT".equals(code)) throw timeout();
            if (response.statusCode() == 503 && "AI_INVALID_RESPONSE".equals(code)) throw invalid();
            throw unavailable();
        } catch (BusinessException ex) { throw ex; }
        catch (TimeoutException ex) { throw timeout(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (ExecutionException ex) {
            if (ex.getCause() instanceof HttpTimeoutException) throw timeout();
            if (ex.getCause() instanceof IllegalArgumentException) throw invalid();
            throw unavailable();
        } catch (com.fasterxml.jackson.core.JacksonException ex) { throw invalid(); }
        catch (Exception ex) { throw unavailable(); }
        finally { if (task != null && !task.isDone()) task.cancel(true); }
    }
    private static BusinessException unavailable() { return new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "模型连接测试暂不可用，请检查服务端连接配置"); }
    private static BusinessException timeout() { return new BusinessException(504, "AI_TIMEOUT", "模型连接测试超时，请主动重试"); }
    private static BusinessException invalid() { return new BusinessException(503, "AI_INVALID_RESPONSE", "模型返回格式无效"); }

    private static class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 16384) { subscription.cancel(); result.completeExceptionally(new IllegalArgumentException()); return; }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
