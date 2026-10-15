package com.openitsm;

import com.openitsm.config.AccountMailSchedulingConfig;
import com.openitsm.mapper.AuthMapper;
import com.openitsm.mapper.NotificationMapper;
import com.openitsm.mapper.UserMapper;
import com.openitsm.service.AccountMailDispatchService;
import com.openitsm.service.SmtpMailService;
import com.openitsm.service.impl.AccountMailCipher;
import com.openitsm.service.impl.AccountMailDispatchServiceImpl;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.transaction.PlatformTransactionManager;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountMailDispatchDisabledTest {
    @Test void disabledServiceDoesNotReadKeysClaimTasksOrContactSmtp() {
        var helper = new AccountMailSmtpTest();
        var config = helper.config(false, "", "", helper.localProperties(2525));
        var tasks = mock(NotificationMapper.class);
        var cipher = mock(AccountMailCipher.class);
        var smtp = mock(SmtpMailService.class);
        var dispatch = new AccountMailDispatchServiceImpl(tasks, mock(UserMapper.class), mock(AuthMapper.class),
            cipher, smtp, config, Clock.systemUTC(), mock(PlatformTransactionManager.class));
        assertThat(dispatch.dispatchPending()).isZero();
        verifyNoInteractions(tasks, cipher, smtp);
    }

    @Test void schedulerIsAbsentByDefaultAndPresentOnlyWhenExplicitlyEnabled() {
        var dispatch = mock(AccountMailDispatchService.class);
        var runner = new ApplicationContextRunner().withUserConfiguration(AccountMailSchedulingConfig.class)
            .withBean(AccountMailDispatchService.class, () -> dispatch);
        runner.run(context -> assertThat(context).doesNotHaveBean(AccountMailSchedulingConfig.class));
        runner.withPropertyValues("openitsm.account-mail.enabled=true", "openitsm.account-mail.initial-delay-ms=3600000")
            .run(context -> {
                assertThat(context).hasSingleBean(AccountMailSchedulingConfig.class);
                context.getBean(AccountMailSchedulingConfig.class).poll();
            });
        verify(dispatch, times(1)).dispatchPending();
    }
}
