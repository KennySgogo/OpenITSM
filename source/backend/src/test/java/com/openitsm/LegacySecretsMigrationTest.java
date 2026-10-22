package com.openitsm;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.PostgreSQLContainer;
import static org.assertj.core.api.Assertions.*;

class LegacySecretsMigrationTest {
    @TempDir Path folder;

    private String encrypted(String value, String aad, byte[] key) throws Exception {
        var nonce = new byte[12]; new java.security.SecureRandom().nextBytes(nonce);
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(nonce) + "." + Base64.getEncoder().encodeToString(cipher.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private int migrate(Path properties, Path key, boolean apply) throws Exception {
        String driver = Path.of(org.postgresql.Driver.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        var args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
            "-cp", driver, "scripts/MigrateLegacySecrets.java", properties.toString(), key.toString()));
        if (apply) args.add("--apply");
        var process = new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(folder.resolve("migration.log").toFile()).start();
        assertThat(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        return process.exitValue();
    }

    @Test void upgradeRejectsUnmigratedDataAndPreservesRecoverableSecretsAndBusinessRows() throws Exception {
        System.setProperty("api.version", "1.44");
        try (var pg = new PostgreSQLContainer<>("postgres:17.11")) {
            pg.start();
            Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).target("16").load().migrate();
            var key = new byte[32]; new java.security.SecureRandom().nextBytes(key);
            UUID user = UUID.randomUUID(), task = UUID.randomUUID();
            String settings = "00000000-0000-0000-0000-000000000014", token = "a".repeat(43);
            String payload = encrypted(token, user + ":INVITE:1", key);
            try (var db = DriverManager.getConnection(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()); var sql = db.createStatement()) {
                sql.execute("INSERT INTO app_user(id,name,email_normalized,role,status) VALUES ('" + user + "','Member','migration@example.com','EMPLOYEE','INVITED')");
                try (var insert = db.prepareStatement("INSERT INTO notification_task(id,event_id,recipient_id,template,status,next_attempt_at,sealed_payload,token_generation) VALUES (?,?,?,'INVITE','PENDING',CURRENT_TIMESTAMP,?,1)")) {
                    insert.setObject(1, task); insert.setObject(2, task); insert.setObject(3, user); insert.setString(4, payload); insert.executeUpdate();
                }
                try (var insert = db.prepareStatement("INSERT INTO team_settings(id,team_name,smtp_host,smtp_port,smtp_username,smtp_from,smtp_security,smtp_password,model_base_url,model_name,model_api_key) VALUES (?,'Team','smtp.example.com',465,'sender','sender@example.com','SSL',?,'https://model.example/v1','model',?)")) {
                    insert.setObject(1, UUID.fromString(settings)); insert.setString(2, encrypted("smtp-test-credential", "settings:" + settings + ":smtp", key));
                    insert.setString(3, encrypted("model-test-credential", "settings:" + settings + ":model", key)); insert.executeUpdate();
                }
                var upgrade = Flyway.configure().dataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword()).load();
                assertThatThrownBy(upgrade::migrate).hasStackTraceContaining("Legacy secrets require migration");
                var props = new Properties(); props.setProperty("OPENITSM_DB_URL", pg.getJdbcUrl());
                props.setProperty("OPENITSM_DB_USER", pg.getUsername()); props.setProperty("OPENITSM_DB_PASSWORD", pg.getPassword());
                Path properties = folder.resolve("database.properties"), keyFile = folder.resolve("old.key");
                try (var out = Files.newBufferedWriter(properties)) { props.store(out, "Isolated test database"); }
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(new byte[32]));
                assertThat(migrate(properties, keyFile, true)).isNotZero();
                try (var row = sql.executeQuery("SELECT sealed_payload FROM notification_task")) { row.next(); assertThat(row.getString(1)).isEqualTo(payload); }
                Files.writeString(keyFile, Base64.getEncoder().encodeToString(key));
                assertThat(migrate(properties, keyFile, false)).isZero();
                assertThat(migrate(properties, keyFile, true)).isZero();
                assertThat(migrate(properties, keyFile, true)).isNotZero();
                upgrade.migrate();
                try (var row = sql.executeQuery("SELECT delivery_token FROM notification_task")) { row.next(); assertThat(row.getString(1)).isEqualTo(token); }
                try (var row = sql.executeQuery("SELECT sealed_payload FROM openitsm_legacy_v17.notification_task")) { row.next(); assertThat(row.getString(1)).isEqualTo(payload); }
                try (var row = sql.executeQuery("SELECT smtp_password,model_api_key FROM team_settings")) {
                    row.next(); assertThat(row.getString(1)).isEqualTo("smtp-test-credential"); assertThat(row.getString(2)).isEqualTo("model-test-credential");
                }
                try (var row = sql.executeQuery("SELECT count(*) FROM app_user")) { row.next(); assertThat(row.getInt(1)).isEqualTo(1); }
                assertThat(Files.readString(folder.resolve("migration.log"))).doesNotContain(token, "smtp-test-credential", "model-test-credential");
            }
        }
    }
}
