package com.openitsm.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;

@Configuration
public class PasswordConfig {
    @Bean PasswordEncoder passwordEncoder() {
        // Preserve the existing 310,000 SHA-256 rounds and encoded salt/hash format.
        return Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }
}
