package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.common.utils.TokenCodec;
import com.openitsm.config.AccountMailConfig;
import com.openitsm.entity.NotificationTaskEntity;
import com.openitsm.mapper.AuthMapper;
import com.openitsm.mapper.NotificationMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.mapper.TicketMapper;
import com.openitsm.service.AccountMailDispatchService;
import com.openitsm.service.SmtpMailService;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AccountMailDispatchServiceImpl implements AccountMailDispatchService {
    private static final int MAX_ATTEMPTS = 4;
    private static final int BATCH_SIZE = 10;
    private static final long LEASE_SECONDS = 120;
    private final NotificationMapper tasks;
    private final UserMapper users;
    private final AuthMapper auth;
    private final SmtpMailService smtp;
    private final AccountMailConfig config;
    private final Clock clock;
    private final TicketMapper tickets;
    private final TransactionTemplate claims;
    private final TransactionTemplate deliveries;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.openitsm.service.RuntimeSettingsService runtimeSettings;

    public AccountMailDispatchServiceImpl(NotificationMapper tasks, UserMapper users, AuthMapper auth,
                                         SmtpMailService smtp, AccountMailConfig config,
                                         Clock clock, PlatformTransactionManager transactionManager, TicketMapper tickets) {
        this.tasks = tasks; this.users = users; this.auth = auth;
        this.smtp = smtp; this.config = config; this.clock = clock;
        this.tickets = tickets;
        claims = new TransactionTemplate(transactionManager);
        claims.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        claims.setTimeout(5);
        deliveries = new TransactionTemplate(transactionManager);
        deliveries.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        deliveries.setTimeout(45);
    }

    @Override
    public int dispatchPending() {
        if (runtimeSettings == null) config.requireReady();
        else config.runtimeSender(runtimeSettings.current().smtp());
        int processed = 0;
        while (processed < BATCH_SIZE) {
            // 从数据库领取一条任务
            NotificationTaskEntity task = claims.execute(status -> {
                Instant now = clock.instant();
                tasks.expireExhausted(now, MAX_ATTEMPTS);
                return tasks.claim(now, now.plusSeconds(LEASE_SECONDS), MAX_ATTEMPTS);
            });
            if (task == null) break;
            deliveries.executeWithoutResult(status -> deliver(task));
            processed++;
        }
        return processed;
    }

    private void deliver(NotificationTaskEntity claimed) {
        // Same lock order as activation, resend and disable: user first, then task.
        // This is a separate delivery transaction, never the invitation HTTP transaction.
        var user = users.lock(claimed.recipientId());
        var task = tasks.lockClaim(claimed.id(), claimed.attempts(), clock.instant());
        if (task == null) return; // A newer lease or cancellation owns this task now.
        if ("TICKET".equals(task.template())) {
            if (user == null || !"ACTIVE".equals(user.status)) {
                finish(task, "FAILED", "ACCOUNT_UNAVAILABLE", clock.instant());
                return;
            }
            var ticket = task.ticketId() == null ? null : tickets.selectById(task.ticketId());
            if (ticket == null || ("EMPLOYEE".equals(user.role) && !user.id.equals(ticket.requesterId))) {
                finish(task, "FAILED", "TICKET_NOT_VISIBLE", clock.instant());
                return;
            }
            try {
                smtp.sendTicket(user.emailNormalized, ticket.id, ticket.number, task.ticketAction());
            } catch (BusinessException ex) {
                retry(task, "SMTP_UNAVAILABLE");
                return;
            } catch (IllegalArgumentException ex) {
                finish(task, "FAILED", "MAIL_INVALID", clock.instant());
                return;
            }
            finish(task, "SUCCESS", null, clock.instant());
            return;
        }
        boolean invitation = "INVITE".equals(task.template());
        if (user == null || (!invitation && !"RESET".equals(task.template()))
            || !(invitation ? "INVITED" : "ACTIVE").equals(user.status)) {
            finish(task, "FAILED", "ACCOUNT_UNAVAILABLE", clock.instant());
            return;
        }
        String token = task.deliveryToken();
        if (!token.matches("[A-Za-z0-9_-]{43}")) {
            finish(task, "FAILED", "PAYLOAD_INVALID", clock.instant());
            return;
        }
        var accountToken = auth.findToken(TokenCodec.hash(token), task.template());
        if (accountToken == null || !accountToken.userId().equals(task.recipientId())
            || accountToken.generation() != task.tokenGeneration() || accountToken.usedAt() != null
            || !accountToken.expiresAt().isAfter(clock.instant())) {
            finish(task, "FAILED", "TOKEN_INVALID", clock.instant());
            return;
        }
        try {
            smtp.send(user.emailNormalized, task.template(), token);
        } catch (BusinessException ex) {
            retry(task, "SMTP_UNAVAILABLE");
            return;
        } catch (IllegalArgumentException ex) {
            finish(task, "FAILED", "MAIL_INVALID", clock.instant());
            return;
        }
        finish(task, "SUCCESS", null, clock.instant());
    }

    private void retry(NotificationTaskEntity task, String errorCode) {
        if (task.attempts() >= MAX_ATTEMPTS) finish(task, "FAILED", errorCode, clock.instant());
        else {
            long delay = switch (task.attempts()) { case 1 -> 60; case 2 -> 300; default -> 900; };
            finish(task, "PENDING", errorCode, clock.instant().plusSeconds(delay));
        }
    }
    private void finish(NotificationTaskEntity task, String status, String code, Instant nextAttempt) {
        if (tasks.finish(task.id(), task.attempts(), status, code, nextAttempt) != 1) {
            throw new IllegalStateException("邮件任务领取状态已变化");
        }
    }
}
