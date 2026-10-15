package com.openitsm.config;

import com.openitsm.service.BootstrapService;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Run with bootstrap profile AND spring.main.web-application-type=none. No HTTP initialization endpoint. */
@Component
@Profile("bootstrap")
public class BootstrapRunner implements CommandLineRunner {
    private final BootstrapService service;
    private final String name;
    private final String email;
    public BootstrapRunner(BootstrapService service, @Value("${openitsm.bootstrap-name}") String name,
        @Value("${openitsm.bootstrap-email}") String email) { this.service = service; this.name = name; this.email = email; }

    @Override public void run(String... args) {
        var console = System.console();
        if (console == null) throw new IllegalStateException("Bootstrap requires an interactive console; no default password");
        char[] password = console.readPassword("Administrator password: ");
        char[] confirmation = console.readPassword("Confirm password: ");
        try {
            if (!Arrays.equals(password, confirmation)) throw new IllegalArgumentException("Passwords differ");
            service.initialize(name, email, new String(password));
            console.printf("Administrator initialized. Initialization is permanently closed.%n");
        } finally { Arrays.fill(password, '\0'); Arrays.fill(confirmation, '\0'); }
    }
}
