package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.config.AccountMailConfig;
import com.openitsm.service.SmtpMailService;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class SmtpMailServiceImpl implements SmtpMailService {
    private final JavaMailSender sender;
    private final AccountMailConfig config;

    public SmtpMailServiceImpl(JavaMailSender sender, AccountMailConfig config) {
        this.sender = sender;
        this.config = config;
    }

    @Override
    public void send(String recipient, String purpose, String rawToken) {
        config.requireReady();
        AccountMailConfig.validateAddress(recipient);
        boolean invitation = "INVITE".equals(purpose);
        if ((!invitation && !"RESET".equals(purpose)) || rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            throw new IllegalArgumentException("账号邮件参数无效");
        }
        String link = config.publicBaseUrl() + (invitation ? "/activate" : "/reset-password") + "?token=" + rawToken;
        String subject = invitation ? "邀请你加入 OpenITSM" : "重置你的 OpenITSM 密码";
        String body = "你好，\n\n" + (invitation ? "你受邀加入 OpenITSM。请打开以下链接设置登录密码：" : "请打开以下链接设置新的 OpenITSM 登录密码：")
            + "\n\n" + link + "\n\n链接自申请起 " + (invitation ? "48 小时" : "30 分钟")
            + "内有效，只能使用一次。设置成功后，请返回登录页使用邮箱和密码登录。"
            + "\n\n" + (invitation ? "如果你不认识邀请方，请忽略此邮件。" : "如果你没有申请重置密码，请忽略此邮件。")
            + "\n\nOpenITSM 团队服务工作台\n";
        try {
            var message = sender.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(config.from());
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            message.setHeader("Auto-Submitted", "auto-generated");
            sender.send(message);
        } catch (MailException | MessagingException ex) {
            // Transport exceptions may contain recipients, credentials or message bodies. Do not log or expose them.
            throw new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "账号邮件投递暂不可用，请稍后重试");
        }
    }
}
