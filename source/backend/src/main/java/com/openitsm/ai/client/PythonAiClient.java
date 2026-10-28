package com.openitsm.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.service.SettingsSnapshot;
import com.openitsm.vo.ClassificationSuggestionVO;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
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
        var result = invoke("/internal/v1/model-connection-tests", Map.of("modelConnection", connection(model)));
        if (!"SUCCESS".equals(result.path("status").asText())) throw invalid();
    }

    public record BusinessTypeCandidate(UUID id, String name, String description) {}

    public record SimilarSource(String sourceKey, String title, String text) {}
    public record SimilarMatch(String sourceKey, String reason) {}
    public record SimilarMatches(List<SimilarMatch> items) {}

    public record ReplyDraft(String content) {}
    public ReplyDraft replyDraft(SettingsSnapshot.Model model, String title, String description,
        String businessType, List<SimilarSource> sources) {
        var result = invoke("/internal/v1/reply-drafts", Map.of("modelConnection", connection(model),
            "ticket", Map.of("title", title, "description", description, "businessType", businessType), "sources", sources));
        try { return json.treeToValue(result, ReplyDraft.class); }
        catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException ex) { throw invalid(); }
    }

    public SimilarMatches similarIssues(SettingsSnapshot.Model model, String title, String description,
        String businessType, List<SimilarSource> sources) {
        var result = invoke("/internal/v1/similar-issues", Map.of("modelConnection", connection(model),
            "ticket", Map.of("title", title, "description", description, "businessType", businessType), "sources", sources));
        try { return json.treeToValue(result, SimilarMatches.class); }
        catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException ex) { throw invalid(); }
    }

    public record AssigneeCandidate(UUID id, String skillsDescription, long openCount, long resolvedCount) {}
    public record Recommendation(UUID assigneeId, String reason) {}
    public record Recommendations(java.util.List<Recommendation> items) {}

    public Recommendations recommendAssignees(SettingsSnapshot.Model model, String title, String description,
        String impactScope, String businessType, List<AssigneeCandidate> candidates) {
        var result = invoke("/internal/v1/assignee-recommendations", Map.of("modelConnection", connection(model),
            "ticket", Map.of("title", title, "description", description, "impactScope", impactScope, "businessType", businessType),
            "candidates", candidates));
        try { return json.treeToValue(result, Recommendations.class); }
        catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException ex) { throw invalid(); }
    }

    public ClassificationSuggestionVO suggestClassification(SettingsSnapshot.Model model, String title, String description,
        String impactScope, List<BusinessTypeCandidate> candidates) {
        var types = candidates.stream().map(candidate -> {
            var value = new LinkedHashMap<String, String>();
            value.put("id", candidate.id().toString()); value.put("name", candidate.name());
            if (candidate.description() != null) value.put("description", candidate.description());
            return value;
        }).toList();
        var result = invoke("/internal/v1/classification-suggestions", Map.of("modelConnection", connection(model),
            "ticket", Map.of("title", title, "description", description, "impactScope", impactScope), "businessTypes", types));
        try { return json.treeToValue(result, ClassificationSuggestionVO.class); }
        catch (com.fasterxml.jackson.core.JacksonException | IllegalArgumentException ex) { throw invalid(); }
    }

    private Map<String, String> connection(SettingsSnapshot.Model model) {
        return Map.of("baseUrl", model.baseUrl(), "model", model.model(), "apiKey", model.apiKey());
    }

    private JsonNode invoke(String path, Map<String, ?> payload) {
        String base = env.getProperty("openitsm.ai.service-url", env.getProperty("OPENITSM_AI_SERVICE_URL", "http://127.0.0.1:8001"));
        CompletableFuture<HttpResponse<byte[]>> task = null;
        try {
            URI target = URI.create(base.replaceAll("/+$", "") + path);
            if (target.getHost() == null || target.getUserInfo() != null || target.getQuery() != null || target.getFragment() != null
                || !("http".equals(target.getScheme()) && com.openitsm.service.impl.SettingsValidation.loopback(target.getHost()))) throw unavailable();
            String body = json.writeValueAsString(payload);
            if (body.getBytes(StandardCharsets.UTF_8).length > 131072) throw new BusinessException(503, "AI_REQUEST_TOO_LARGE", "AI 材料过大，请减少材料或人工选择");
            var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(70)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
            task = http.sendAsync(request, info -> new BoundedBodySubscriber());
            var response = task.get(71, TimeUnit.SECONDS);
            var result = json.readTree(response.body());
            if (response.statusCode() == 200) {
                if (result == null || !result.isObject()) throw invalid();
                return result;
            }
            if (result == null) throw invalid();
            String code = result.path("code").asText();
            if (response.statusCode() == 504 && "AI_TIMEOUT".equals(code)) throw timeout();
            if (response.statusCode() == 503 && "AI_INVALID_RESPONSE".equals(code)) throw invalid();
            if (response.statusCode() == 503) {
                switch (code) {
                    case "AI_PROVIDER_AUTH_FAILED" -> throw new BusinessException(503, code, "模型服务认证失败，请管理员检查 API 密钥和访问权限");
                    case "AI_PROVIDER_RATE_LIMITED" -> throw new BusinessException(503, code, "模型服务请求受限，请稍后重试或检查供应商额度");
                    case "AI_PROVIDER_REQUEST_REJECTED" -> throw new BusinessException(503, code, "模型服务拒绝请求，请管理员检查地址、模型名称和请求参数");
                }
            }
            if (response.statusCode() == 503 && "AI_REQUEST_TOO_LARGE".equals(code))
                throw new BusinessException(503, code, "AI 材料过大，请减少材料或人工选择");
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
    private static BusinessException unavailable() { return new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "AI 服务暂不可用，请稍后重试或人工处理"); }
    private static BusinessException timeout() { return new BusinessException(504, "AI_TIMEOUT", "AI 请求超时，请主动重试或人工处理"); }
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
