package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.config.AccountMailConfig;
import com.openitsm.service.SmtpMailService;
import jakarta.mail.MessagingException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class SmtpMailServiceImpl implements SmtpMailService {
    private final JavaMailSender sender;
    private final AccountMailConfig config;
    private final com.openitsm.service.RuntimeSettingsService runtime;

    public SmtpMailServiceImpl(JavaMailSender sender, AccountMailConfig config) {
        this(sender, config, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public SmtpMailServiceImpl(JavaMailSender sender, AccountMailConfig config, com.openitsm.service.RuntimeSettingsService runtime) {
        this.sender = sender;
        this.config = config;
        this.runtime = runtime;
    }

    @Override
    public void send(String recipient, String purpose, String rawToken) {
        if (runtime == null) config.requireReady();
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
        deliver(recipient, subject, body);
    }

    @Override
    public void sendTicket(String recipient, UUID ticketId, String number, String action) {
        if (runtime == null) config.requireReady();
        AccountMailConfig.validateAddress(recipient);
        if (ticketId == null || number == null || !number.matches("ITSM-[0-9]+") || action == null)
            throw new IllegalArgumentException("工单邮件参数无效");
        String label = switch (action) {
            case "assignments" -> "已分派";
            case "transfers" -> "已转派";
            case "replies" -> "新增公开回复";
            case "resolutions" -> "已解决";
            case "close" -> "已关闭";
            case "reopen" -> "已重开";
            default -> throw new IllegalArgumentException("工单邮件动作无效");
        };
        String link = config.publicBaseUrl() + "/tickets/" + ticketId;
        deliver(recipient, "OpenITSM 工单 " + number + "：" + label,
            "你好，\n\n工单 " + number + "：" + label + "。\n\n请登录 OpenITSM 查看详情：\n" + link
                + "\n\n此邮件由系统自动发送，请勿直接回复。\n\nOpenITSM 团队服务工作台\n");
    }

    private void deliver(String recipient, String subject, String body) {
        JavaMailSender transport = sender;
        String from = config.from();
        if (runtime != null) {
            var snapshot = runtime.current();
            transport = config.runtimeSender(snapshot.smtp());
            from = snapshot.smtp().from();
        }
        try {
            var message = transport.createMimeMessage();
            var helper = new MimeMessageHelper(message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(from);
            helper.setTo(recipient);
            helper.setSubject(subject);
            helper.setText(body, false);
            message.setHeader("Auto-Submitted", "auto-generated");
            transport.send(message);
        } catch (MailException | MessagingException ex) {
            // Transport exceptions may contain recipients, credentials or message bodies. Do not log or expose them.
            throw new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "账号邮件投递暂不可用，请稍后重试");
        }
    }
}
