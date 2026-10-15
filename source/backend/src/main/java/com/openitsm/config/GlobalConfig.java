package com.openitsm.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GlobalConfig {
    @Bean Clock clock() { return Clock.systemUTC(); }

}
