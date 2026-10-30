package com.openitsm;

import com.openitsm.common.utils.TokenCodec;
import org.junit.jupiter.api.Test;
import com.openitsm.common.utils.Values;
import com.openitsm.common.exception.BusinessException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class M01ContractTest {
    @Test
    void unicodeLengthUsesCodePointsAndPasswordsAreNotTrimmed() {
        String name = "😀".repeat(80);
        assertThat(Values.text(name, "name", 1, 80)).isEqualTo(name);
        assertThatThrownBy(() -> Values.text(name + "😀", "name", 1, 80)).isInstanceOf(BusinessException.class);
        String password = "  Password1234  ";
        assertThat(Values.password(password, password)).isEqualTo(password);
        assertThat(Values.password("admin@admin.com", "admin@admin.com")).isEqualTo("admin@admin.com");
        assertThatThrownBy(() -> Values.password("12345", "12345")).isInstanceOf(BusinessException.class);
    }

    @Test
    void accountTokensHave256BitEntropyAndDigestsVerifyIdentity() {
        String token = TokenCodec.token();
        assertThat(java.util.Base64.getUrlDecoder().decode(token)).hasSize(32);
        assertThat(TokenCodec.hash(token)).hasSize(64).isNotEqualTo(token);
        assertThat(TokenCodec.sameHash(TokenCodec.hash(token), TokenCodec.hash(token))).isTrue();
        assertThat(TokenCodec.sameHash(TokenCodec.hash(token), TokenCodec.hash(TokenCodec.token()))).isFalse();
    }
}
