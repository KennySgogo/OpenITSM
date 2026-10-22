import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import javax.crypto.Cipher;
import javax.crypto.spec.*;

/** One-time, offline V16 -> V17 data migration. Stop the backend before applying. */
public class MigrateLegacySecrets {
    static String open(String value, String aad, byte[] key) throws Exception {
        if (value == null || value.isEmpty()) return value;
        String[] parts = value.split("\\.", -1);
        if (parts.length != 2) throw new IllegalStateException("Unexpected legacy payload; database unchanged");
        var cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
    }
    public static void main(String[] args) throws Exception {
        if (args.length < 2 || args.length > 3 || (args.length == 3 && !args[2].equals("--apply")))
            throw new IllegalArgumentException("Usage: MigrateLegacySecrets.java <properties-file> <old-key-file> [--apply]");
        var props = new Properties();
        try (var reader = Files.newBufferedReader(Path.of(args[0]))) { props.load(reader); }
        byte[] key = Base64.getDecoder().decode(Files.readString(Path.of(args[1])).strip());
        try (var db = DriverManager.getConnection(props.getProperty("OPENITSM_DB_URL"), props.getProperty("OPENITSM_DB_USER"), props.getProperty("OPENITSM_DB_PASSWORD"))) {
            db.setAutoCommit(false);
            try (var sql = db.createStatement()) {
                sql.execute("LOCK TABLE notification_task IN ACCESS EXCLUSIVE MODE");
                boolean hasSettings;
                try (var row = sql.executeQuery("SELECT to_regclass('public.team_settings') IS NOT NULL")) { row.next(); hasSettings = row.getBoolean(1); }
                if (hasSettings) sql.execute("LOCK TABLE team_settings IN ACCESS EXCLUSIVE MODE");
                // The schema marker prevents a second run from trying to decrypt migrated values.
                try (var row = sql.executeQuery("SELECT to_regclass('openitsm_legacy_v17.notification_task') IS NOT NULL")) {
                    row.next(); if (row.getBoolean(1)) throw new IllegalStateException("Migration already applied; database unchanged");
                }
                var accountRows = new ArrayList<Object[]>();
                try (var rows = sql.executeQuery("SELECT id,recipient_id,template,token_generation,sealed_payload FROM notification_task WHERE template IN ('INVITE','RESET')")) {
                    while (rows.next()) {
                        String token = open(rows.getString(5), rows.getObject(2) + ":" + rows.getString(3) + ":" + rows.getLong(4), key);
                        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw new IllegalStateException("Invalid account payload; database unchanged");
                        accountRows.add(new Object[]{rows.getObject(1), token});
                    }
                }
                var settingRows = new ArrayList<Object[]>();
                if (hasSettings) try (var rows = sql.executeQuery("SELECT id,smtp_password,model_api_key FROM team_settings")) {
                    while (rows.next()) settingRows.add(new Object[]{rows.getObject(1), open(rows.getString(2), "settings:" + rows.getObject(1) + ":smtp", key), open(rows.getString(3), "settings:" + rows.getObject(1) + ":model", key)});
                }
                if (args.length == 3) {
                    sql.execute("CREATE SCHEMA IF NOT EXISTS openitsm_legacy_v17");
                    sql.execute("REVOKE ALL ON SCHEMA openitsm_legacy_v17 FROM PUBLIC");
                    sql.execute("CREATE TABLE openitsm_legacy_v17.notification_task AS TABLE notification_task");
                    if (hasSettings) sql.execute("CREATE TABLE openitsm_legacy_v17.team_settings AS TABLE team_settings");
                    try (var update = db.prepareStatement("UPDATE notification_task SET sealed_payload=? WHERE id=?")) {
                        for (var row : accountRows) { update.setString(1, (String)row[1]); update.setObject(2, row[0]); update.addBatch(); }
                        update.executeBatch();
                    }
                    if (hasSettings) try (var update = db.prepareStatement("UPDATE team_settings SET smtp_password=?,model_api_key=? WHERE id=?")) {
                        for (var row : settingRows) { update.setString(1, (String)row[1]); update.setString(2, (String)row[2]); update.setObject(3, row[0]); update.addBatch(); }
                        update.executeBatch();
                    }
                    db.commit();
                } else db.rollback();
                System.out.printf("%s: validated %d account payloads and %d settings rows; no credentials printed%n", args.length == 3 ? "APPLIED WITH RECOVERY COPY" : "DRY RUN", accountRows.size(), settingRows.size());
            } catch (Exception failure) {
                db.rollback();
                throw new IllegalStateException("Legacy migration failed; transaction rolled back. Verify key, schema and database access.");
            }
        } finally { Arrays.fill(key, (byte)0); }
    }
}
