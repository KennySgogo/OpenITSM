package com.openitsm.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.openitsm.ai.client.PythonAiClient;
import com.openitsm.common.exception.BusinessException;
import com.openitsm.entity.SettingsEntity;
import com.openitsm.mapper.*;
import com.openitsm.service.*;
import com.openitsm.vo.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SettingsServiceImpl implements SettingsService {
    private final SettingsMapper settings;
    private final AiConnectionStatusMapper statuses;
    private final UserMapper users;
    private final TransactionLockMapper locks;
    private final SessionService sessions;
    private final RuntimeSettingsService runtime;
    private final RateLimitService limits;
    private final PythonAiClient python;
    private final Clock clock;
    private final Environment env;
    private final TransactionTemplate transaction;

    public SettingsServiceImpl(SettingsMapper settings, AiConnectionStatusMapper statuses, UserMapper users,
        TransactionLockMapper locks, SessionService sessions, RuntimeSettingsService runtime,
        RateLimitService limits, PythonAiClient python, Clock clock, Environment env,
        PlatformTransactionManager transactions) {
        this.settings = settings; this.statuses = statuses; this.users = users; this.locks = locks;
        this.sessions = sessions; this.runtime = runtime; this.limits = limits;
        this.python = python; this.clock = clock; this.env = env; this.transaction = new TransactionTemplate(transactions);
    }
    private void requireAdmin(AuthContext context, boolean lock) {
        if (lock) locks.lock("MEMBERS", "CONTROL");
        var user = lock ? users.lock(context.user().id) : users.selectById(context.user().id);
        if (user == null || !"ACTIVE".equals(user.status) || user.authVersion != context.session().authVersion())
            throw new BusinessException(401, "SESSION_EXPIRED", "会话已失效，请重新登录");
        if (!"ADMIN".equals(user.role)) throw new BusinessException(403, "FORBIDDEN", "仅管理员可管理配置");
    }
    @Transactional(readOnly = true)
    public SettingsVO detail(AuthContext context) {
        requireAdmin(context, false);
        var value = runtime.current(); var smtp = value.smtp(); var model = value.model();
        return new SettingsVO(SettingsEntity.ID, value.version(), value.teamName(),
            new SettingsVO.SmtpVO(smtp.host(), smtp.port(), smtp.username(), smtp.from(), smtp.security(), configured(smtp.password())),
            new SettingsVO.ModelVO(model.baseUrl(), model.model(), configured(model.apiKey())), value.aiEnabled(),
            status("TEST", value.version()), status("CALL", value.version()));
    }
    private SettingsVO.StatusVO status(String kind, long version) {
        var status = statuses.find(kind);
        if (status == null) return new SettingsVO.StatusVO("TEST".equals(kind) ? "NOT_TESTED" : "NO_CALLS", null, null, false);
        String state = "RUNNING".equals(status.status()) && status.occurredAt().plusSeconds(20).isBefore(clock.instant()) ? "AI_TIMEOUT" : status.status();
        return new SettingsVO.StatusVO(state, status.configVersion(), status.occurredAt(), status.configVersion() == version);
    }
    @Transactional
    public ActionVO update(AuthContext context, JsonNode body) {
        SettingsValidation.object(body, Set.of("teamName", "smtp", "model", "aiEnabled"), "body");
        requireAdmin(context, true);
        locks.lock("SETTINGS", "SINGLETON");
        var old = runtime.current();
        String team = SettingsValidation.text(body, "teamName", old.teamName(), 80, false);
        var smtp = old.smtp(); var model = old.model();
        if (body.has("smtp")) {
            var part = body.get("smtp");
            SettingsValidation.object(part, Set.of("host", "port", "username", "from", "security", "password", "clearPassword"), "smtp");
            int port = smtp.port();
            if (part.has("port")) {
                if (!part.get("port").isIntegralNumber() || !part.get("port").canConvertToInt()) throw BusinessException.field("smtp.port", "端口须为整数");
                port = part.get("port").intValue();
            }
            smtp = new SettingsSnapshot.Smtp(SettingsValidation.text(part, "host", smtp.host(), 253, true), port,
                SettingsValidation.text(part, "username", smtp.username(), 320, true), SettingsValidation.text(part, "from", smtp.from(), 320, true),
                SettingsValidation.text(part, "security", smtp.security(), 16, false), SettingsValidation.secret(part, "password", "clearPassword", smtp.password()));
        }
        if (body.has("model")) {
            var part = body.get("model");
            SettingsValidation.object(part, Set.of("baseUrl", "model", "apiKey", "clearApiKey"), "model");
            model = new SettingsSnapshot.Model(SettingsValidation.text(part, "baseUrl", model.baseUrl(), 2048, true),
                SettingsValidation.text(part, "model", model.model(), 200, true), SettingsValidation.secret(part, "apiKey", "clearApiKey", model.apiKey()));
        }
        boolean local = env.matchesProfiles("local", "test");
        SettingsValidation.smtp(smtp, local); SettingsValidation.modelUrl(model.baseUrl(), local);
        boolean enabled = SettingsValidation.bool(body, "aiEnabled", old.aiEnabled());
        if (enabled && !model.configured()) throw new BusinessException(409, "INVALID_STATE", "请先配置完整模型连接，或同时关闭 AI");
        var row = new SettingsEntity(); row.version = old.version() + 1; row.teamName = team;
        row.smtpHost = smtp.host(); row.smtpPort = smtp.port(); row.smtpUsername = smtp.username(); row.smtpFrom = smtp.from(); row.smtpSecurity = smtp.security();
        row.smtpPassword = smtp.password(); row.modelBaseUrl = model.baseUrl(); row.modelName = model.model();
        row.modelApiKey = model.apiKey(); row.aiEnabled = enabled;
        if (settings.selectById(SettingsEntity.ID) == null) settings.insert(row); else settings.updateById(row);
        sessions.touch(context.session().hash());
        return new ActionVO(SettingsEntity.ID);
    }
    private static boolean configured(String value) { return value != null && !value.isBlank(); }

    public ModelConnectionTestVO testModel(AuthContext context) {
        UUID attempt = UUID.randomUUID();
        var snapshot = transaction.execute(tx -> {
            requireAdmin(context, true);
            locks.lock("SETTINGS", "SINGLETON");
            // Only a saved database configuration may be tested, never an unsaved browser form.
            if (settings.selectById(SettingsEntity.ID) == null) throw new BusinessException(503, "AI_NOT_CONFIGURED", "请先保存模型连接");
            var value = runtime.current();
            if (!value.model().configured()) throw new BusinessException(503, "AI_NOT_CONFIGURED", "模型连接尚未配置完整");
            limits.check("MODEL_CONNECTION_TEST", context.user().id.toString(), 1, Duration.ofSeconds(1), 1);
            statuses.begin("TEST", value.version(), clock.instant(), attempt);
            sessions.touch(context.session().hash());
            return value;
        });
        BusinessException failure = null;
        try { python.testConnection(snapshot.model()); }
        catch (BusinessException ex) { failure = ex; }
        String state = failure == null ? "SUCCESS" : failure.code;
        Instant at = clock.instant();
        transaction.executeWithoutResult(tx -> statuses.finish("TEST", attempt, state, at));
        if (failure != null) throw failure;
        return transaction.execute(tx -> {
            requireAdmin(context, false);
            return new ModelConnectionTestVO(state, snapshot.version(), at, runtime.current().version() == snapshot.version());
        });
    }
}
