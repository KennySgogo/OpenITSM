package com.openitsm.config;

import com.openitsm.common.exception.BusinessException;
import jakarta.mail.internet.InternetAddress;
import java.net.URI;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.mail.MailProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
@EnableConfigurationProperties(MailProperties.class)
public class AccountMailConfig {
    private final String from;
    private final String publicBaseUrl;
    private final MailProperties properties;
    private final Environment environment;

    public AccountMailConfig(@Value("${openitsm.account-mail.from:}") String from,
                             @Value("${openitsm.account-mail.public-base-url:}") String publicBaseUrl,
                             MailProperties properties, Environment environment) {
        this.from = from;
        this.publicBaseUrl = publicBaseUrl;
        this.properties = properties;
        this.environment = environment;
    }

    @Bean
    public JavaMailSenderImpl javaMailSender() {
        var sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        if (properties.getPort() != null) sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        sender.setProtocol(properties.getProtocol());
        sender.setDefaultEncoding("UTF-8");
        var smtp = new Properties();
        smtp.putAll(properties.getProperties());
        if (properties.getSsl().isEnabled()) smtp.setProperty("mail.smtp.ssl.enable", "true");
        // Bound every network operation; never disable certificate or hostname validation.
        smtp.setProperty("mail.smtp.connectiontimeout", "5000");
        smtp.setProperty("mail.smtp.timeout", "10000");
        smtp.setProperty("mail.smtp.writetimeout", "10000");
        smtp.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        sender.setJavaMailProperties(smtp);
        return sender;
    }

    public void requireReady() {
        try {
            if (blank(properties.getHost()) || properties.getPort() == null
                || properties.getPort() < 1 || properties.getPort() > 65535) throw new IllegalArgumentException();
            validateAddress(from);
            URI base = URI.create(publicBaseUrl);
            if (base.getHost() == null || base.getRawUserInfo() != null || base.getRawQuery() != null
                || base.getRawFragment() != null) throw new IllegalArgumentException();
            boolean local = environment.acceptsProfiles(Profiles.of("local", "test"));
            if (!"https".equals(base.getScheme()) && !(local && "http".equals(base.getScheme()) && loopback(base.getHost()))) {
                throw new IllegalArgumentException();
            }
            var smtp = properties.getProperties();
            boolean ssl = Boolean.parseBoolean(smtp.get("mail.smtp.ssl.enable")) || properties.getSsl().isEnabled();
            boolean startTls = Boolean.parseBoolean(smtp.get("mail.smtp.starttls.enable"))
                && Boolean.parseBoolean(smtp.get("mail.smtp.starttls.required"));
            boolean localPeer = local && loopback(properties.getHost());
            if ((!ssl && !startTls && !localPeer) || !blank(smtp.get("mail.smtp.ssl.trust"))) throw new IllegalArgumentException();
            if (!"smtp".equals(properties.getProtocol())) throw new IllegalArgumentException();
            boolean auth = Boolean.parseBoolean(smtp.getOrDefault("mail.smtp.auth", "true"));
            if (auth && (blank(properties.getUsername()) || blank(properties.getPassword()))) throw new IllegalArgumentException();
            if (!auth && !localPeer) throw new IllegalArgumentException();
        } catch (Exception ex) {
            throw new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "账号邮件发送配置暂不可用，请联系管理员");
        }
    }

    public String from() { return from; }
    public boolean localProfile() { return environment.matchesProfiles("local", "test"); }

    /** Validate a per-delivery snapshot without changing the shared sender. */
    public JavaMailSenderImpl runtimeSender(com.openitsm.service.SettingsSnapshot.Smtp value) {
        var mail = new MailProperties();
        mail.setHost(value.host()); mail.setPort(value.port()); mail.setUsername(value.username()); mail.setPassword(value.password());
        mail.getProperties().put("mail.smtp.ssl.enable", Boolean.toString("SSL".equals(value.security())));
        mail.getProperties().put("mail.smtp.starttls.enable", Boolean.toString("STARTTLS".equals(value.security())));
        mail.getProperties().put("mail.smtp.starttls.required", Boolean.toString("STARTTLS".equals(value.security())));
        boolean localPeer = localProfile() && loopback(value.host());
        mail.getProperties().put("mail.smtp.auth", Boolean.toString(!localPeer || !blank(value.username())));
        var effective = new AccountMailConfig(value.from(), publicBaseUrl, mail, environment);
        effective.requireReady();
        return effective.javaMailSender();
    }
    public String publicBaseUrl() { return publicBaseUrl.replaceAll("/+$", ""); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static boolean loopback(String host) { return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host) || "::1".equals(host); }
    public static void validateAddress(String address) {
        try {
            if (address == null || address.contains("\r") || address.contains("\n")) throw new IllegalArgumentException();
            var parsed = new InternetAddress(address, true);
            parsed.validate();
            if (parsed.getPersonal() != null || !parsed.getAddress().equals(address)) throw new IllegalArgumentException();
        } catch (Exception ex) { throw new IllegalArgumentException("邮件地址格式无效"); }
    }
}
