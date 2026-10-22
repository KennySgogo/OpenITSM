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
    private final MailProperties mail;
    private final Environment env;
    public RuntimeSettingsServiceImpl(SettingsMapper settings, MailProperties mail, Environment env) {
        this.settings = settings; this.mail = mail; this.env = env;
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
            new SettingsSnapshot.Smtp(row.smtpHost, row.smtpPort, row.smtpUsername, row.smtpFrom, row.smtpSecurity, row.smtpPassword),
            new SettingsSnapshot.Model(row.modelBaseUrl, row.modelName, row.modelApiKey), row.aiEnabled);
    }
    private static String value(String value) { return value == null ? "" : value; }
}
