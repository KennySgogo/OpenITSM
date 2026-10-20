package com.openitsm.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.config.AccountMailConfig;
import com.openitsm.service.SettingsSnapshot;
import java.net.URI;
import java.util.Set;

/** Validation of the partial settings document; absence differs from null and explicit clear. */
public final class SettingsValidation {
    private SettingsValidation() {}
    public static void object(JsonNode body, Set<String> allowed, String field) {
        if (body == null || !body.isObject() || body.isEmpty()) throw BusinessException.field(field, "请提供需要更新的配置");
        body.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw BusinessException.field(field + "." + name, "不支持的字段"); });
    }
    public static String text(JsonNode body, String key, String previous, int max, boolean empty) {
        if (!body.has(key)) return previous;
        var value = body.get(key);
        if (!value.isTextual() || value.textValue().length() > max || (!empty && value.textValue().isBlank())
            || value.textValue().chars().anyMatch(Character::isISOControl)) throw BusinessException.field(key, "字段格式或长度不正确");
        return value.textValue().strip();
    }
    public static boolean bool(JsonNode body, String key, boolean previous) {
        if (!body.has(key)) return previous;
        if (!body.get(key).isBoolean()) throw BusinessException.field(key, "必须是布尔值");
        return body.get(key).asBoolean();
    }
    public static String secret(JsonNode body, String key, String clear, String previous) {
        boolean clearing = bool(body, clear, false);
        if (clearing && body.has(key)) throw BusinessException.field(key, "清除和替换凭据不能同时提交");
        if (clearing) return null;
        if (!body.has(key)) return previous;
        String value = text(body, key, previous, 512, false);
        if (value.matches("[*•●]+")) throw BusinessException.field(key, "请填写真实凭据，不接受掩码");
        return body.get(key).textValue();
    }
    public static void smtp(SettingsSnapshot.Smtp smtp, boolean local) {
        if (!Set.of("SSL", "STARTTLS", "NONE").contains(smtp.security())) throw BusinessException.field("smtp.security", "连接安全模式无效");
        if (smtp.port() < 1 || smtp.port() > 65535) throw BusinessException.field("smtp.port", "端口须为 1～65535");
        if (!smtp.host().isEmpty() && !smtp.host().matches("[A-Za-z0-9.:-]{1,253}")) throw BusinessException.field("smtp.host", "服务器地址无效");
        if (!smtp.from().isEmpty()) {
            try { AccountMailConfig.validateAddress(smtp.from()); }
            catch (IllegalArgumentException ex) { throw BusinessException.field("smtp.from", "发件邮箱格式无效"); }
        }
        if (!smtp.host().isEmpty() && "NONE".equals(smtp.security()) && !(local && loopback(smtp.host())))
            throw BusinessException.field("smtp.security", "外部 SMTP 必须使用 SSL 或 STARTTLS");
    }
    public static void modelUrl(String url, boolean local) {
        if (url.isEmpty()) return;
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || !("https".equals(uri.getScheme()) || (local && "http".equals(uri.getScheme()) && loopback(uri.getHost())))) throw new IllegalArgumentException();
        } catch (Exception ex) { throw BusinessException.field("model.baseUrl", "模型地址须为 HTTPS；本机测试允许 loopback HTTP"); }
    }
    public static boolean loopback(String host) { return Set.of("localhost", "127.0.0.1", "[::1]", "::1").contains(host.toLowerCase(java.util.Locale.ROOT)); }
}
