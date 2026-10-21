package com.openitsm.service;

public interface RuntimeSettingsService {
    SettingsSnapshot current();

    default SettingsSnapshot requireAiConnection() {
        var value = current();
        if (!value.aiEnabled()) throw new com.openitsm.common.exception.BusinessException(503, "AI_DISABLED", "AI 已关闭，可继续人工处理");
        if (!value.model().configured()) throw new com.openitsm.common.exception.BusinessException(503, "AI_NOT_CONFIGURED", "模型连接尚未配置完整");
        return value;
    }

    /** Business callers must recheck before returning suggestions; testing is the explicit exception. */
    default void recheckAiConnection(long version) {
        var value = requireAiConnection();
        if (value.version() != version) throw new com.openitsm.common.exception.BusinessException(503, "DEPENDENCY_UNAVAILABLE", "AI 配置已变化，请重新请求");
    }
}
