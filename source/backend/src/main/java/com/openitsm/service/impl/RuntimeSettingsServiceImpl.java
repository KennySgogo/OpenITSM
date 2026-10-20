package com.openitsm.service.impl;

import com.openitsm.entity.SettingsEntity;
import com.openitsm.mapper.SettingsMapper;
import com.openitsm.service.RuntimeSettingsService;
import com.openitsm.service.SettingsSnapshot;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

@Service
public class RuntimeSettingsServiceImpl implements RuntimeSettingsService {
    private final SettingsMapper settings;
    private final AccountMailCipher cipher;
    private final MailProperties mail;
    private final Environment env;
    public RuntimeSettingsServiceImpl(SettingsMapper settings, AccountMailCipher cipher, MailProperties mail, Environment env) {
        this.settings = settings; this.cipher = cipher; this.mail = mail; this.env = env;
    }
    public SettingsSnapshot current() {
        var row = settings.selectById(SettingsEntity.ID);
        if (row == null) {
            boolean ssl = mail.getSsl().isEnabled() || Boolean.parseBoolean(mail.getProperties().get("mail.smtp.ssl.enable"));
            boolean tls = Boolean.parseBoolean(mail.getProperties().get("mail.smtp.starttls.enable"))
                && Boolean.parseBoolean(mail.getProperties().get("mail.smtp.starttls.required"));
            return new SettingsSnapshot(0, env.getProperty("openitsm.team-name", "OpenITSM"),
                new SettingsSnapshot.Smtp(value(mail.getHost()), mail.getPort() == null ? 465 : mail.getPort(),
                    value(mail.getUsername()), env.getProperty("openitsm.account-mail.from", ""), ssl ? "SSL" : tls ? "STARTTLS" : "NONE", mail.getPassword()),
                new SettingsSnapshot.Model(env.getProperty("openitsm.model.base-url", ""), env.getProperty("openitsm.model.name", ""),
                    env.getProperty("openitsm.model.api-key", "")), false);
        }
        return new SettingsSnapshot(row.version, row.teamName,
            new SettingsSnapshot.Smtp(row.smtpHost, row.smtpPort, row.smtpUsername, row.smtpFrom, row.smtpSecurity, open(row.smtpPassword, "smtp")),
            new SettingsSnapshot.Model(row.modelBaseUrl, row.modelName, open(row.modelApiKey, "model")), row.aiEnabled);
    }
    private String open(String sealed, String purpose) {
        try { return sealed == null ? null : cipher.open(sealed, "settings:" + SettingsEntity.ID + ":" + purpose); }
        catch (Exception ex) { throw new com.openitsm.common.exception.BusinessException(503, "DEPENDENCY_UNAVAILABLE", "设置凭据暂不可用，请联系管理员"); }
    }
    private static String value(String value) { return value == null ? "" : value; }
}
