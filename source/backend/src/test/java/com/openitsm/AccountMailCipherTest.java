package com.openitsm;

import com.openitsm.service.impl.AccountMailCipher;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AccountMailCipherTest {
    @Test void decryptsOnlyWithTheOriginalAssociatedDataAndAuthenticPayload() {
        var cipher = new AccountMailCipher(M01IntegrationTest.KEY_FILE.toString());
        String token = "a".repeat(43);
        String sealed = cipher.seal(token, "user:INVITE:1");
        assertThat(cipher.open(sealed, "user:INVITE:1")).isEqualTo(token);
        assertThatThrownBy(() -> cipher.open(sealed, "user:INVITE:2")).isInstanceOf(IllegalArgumentException.class).hasNoCause();
        assertThatThrownBy(() -> cipher.open("invalid", "user:INVITE:1")).isInstanceOf(IllegalArgumentException.class).hasNoCause();
    }
}
