package com.openitsm.service.impl;

import com.openitsm.common.exception.BusinessException;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class AccountMailCipher {
    private static final Logger log = LoggerFactory.getLogger(AccountMailCipher.class);
    private final String keyFile;
    public AccountMailCipher(@Value("${openitsm.account-mail-key-file:}") String keyFile) { this.keyFile = keyFile; }
    public void requireReady() { readKey(); }
    private byte[] readKey() {
        try {
            byte[] key = Base64.getDecoder().decode(Files.readString(Path.of(keyFile)).strip());
            if (key.length != 32) throw new IllegalArgumentException("key size");
            return key;
        } catch (Exception ex) {
            log.info("DEPENDENCY_UNAVAILABLE");
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

    public String open(String sealed, String aad) {
        byte[] key = readKey();
        try {
            if (sealed == null || sealed.length() > 1024) throw new IllegalArgumentException();
            String[] parts = sealed.split("\\.", -1);
            if (parts.length != 2) throw new IllegalArgumentException();
            byte[] nonce = Base64.getDecoder().decode(parts[0]);
            if (nonce.length != 12) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalArgumentException("账号邮件内容无法解密");
        } finally { java.util.Arrays.fill(key, (byte) 0); }
    }
}
