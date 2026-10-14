package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccountMailCipher {
    private final String keyFile;
    public AccountMailCipher(@Value("${openitsm.account-mail-key-file:}") String keyFile) { this.keyFile = keyFile; }
    public void requireReady() { readKey(); }
    private byte[] readKey() {
        try {
            byte[] key = Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).strip());
            if (key.length != 32) throw new IllegalArgumentException("key size");
            return key;
        } catch (Exception ex) {
            throw new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "账号邮件加密配置暂不可用，请联系管理员");
        }
    }

    public String seal(String plain, String aad) {
        try {
            byte[] key = readKey();
            byte[] nonce = new byte[12];
            new SecureRandom().nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(plain.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new BusinessException(503, "DEPENDENCY_UNAVAILABLE", "账号邮件加密配置暂不可用，请联系管理员");
        }
    }
}
