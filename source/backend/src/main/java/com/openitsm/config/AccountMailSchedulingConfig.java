package com.openitsm.config;

import com.openitsm.common.exception.BusinessException;
import com.openitsm.service.AccountMailDispatchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix="openitsm.account-mail", name="enabled", havingValue="true")
public class AccountMailSchedulingConfig {
    private static final Logger log = LoggerFactory.getLogger(AccountMailSchedulingConfig.class);
    private final AccountMailDispatchService dispatch;
    private String lastFailure;
    public AccountMailSchedulingConfig(AccountMailDispatchService dispatch) { this.dispatch = dispatch; }

    @Scheduled(fixedDelayString="${openitsm.account-mail.poll-interval-ms:5000}",
               initialDelayString="${openitsm.account-mail.initial-delay-ms:5000}")
    public void poll() {
        try {
            dispatch.dispatchPending();
            lastFailure = null;
        } catch (RuntimeException ex) {
            String code = ex instanceof BusinessException failure ? failure.code : "DISPATCH_FAILED";
            if (!code.equals(lastFailure)) {
                // Exception messages may contain payloads or credentials. Log only a classification.
                log.warn("账号邮件调度暂不可用 code={} type={}", code, ex.getClass().getSimpleName());
                lastFailure = code;
            }
        }
    }
}
