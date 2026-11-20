package deploy.tools;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * DEPLOY-BUNDLE operator tool for a prod deployment (which intentionally has
 * no dev seeding surface): manages the FIRST app_user directly against the
 * production PostgreSQL over plain JDBC -- the same Argon2 hash column the
 * server verifies against (com.keplura.server.identity.User).
 *
 * Commands (password/hash ALWAYS via stdin or --password-env, never argv):
 *   --url jdbc:postgresql://... --user ... [--password-env VAR] seed \
 *       --email E [--display-name N]
 *       Creates the account idempotently (existing email => skipped, the
 *       password is NEVER overwritten -- same rule as DevAccountSeeder).
 *   ... verify --email E
 *       Exit 0 iff a user row exists for E (used by the backup/restore
 *       proof: after a restore the login account must still be there).
 *
 * The hash for seed is computed IN THIS TOOL with the server's exact encoder
 * (Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8) from a password read
 * on stdin -- mirroring deploy/tools/PasswordHashTool.java.
 */
public final class AccountTool {

    private AccountTool() {
    }

    public static void main(String[] argv) throws Exception {
        Map<String, String> opts = new HashMap<>();
        String command = null;
        for (int i = 0; i < argv.length; i++) {
            String a = argv[i];
            if (a.startsWith("--")) {
                int eq = a.indexOf('=');
                if (eq > 0) {
                    opts.put(a.substring(2, eq), a.substring(eq + 1));
                } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                    opts.put(a.substring(2), argv[++i]);
                } else {
                    command = a.substring(2);
                }
            } else if (command == null) {
                command = a;
            }
        }
        String url = require(opts, "url");
        String dbUser = require(opts, "user");
        String dbPassword = opts.containsKey("password-env")
                ? System.getenv(opts.get("password-env"))
                : "";

        try (Connection conn = DriverManager.getConnection(url, dbUser, dbPassword == null ? "" : dbPassword)) {
            switch (command == null ? "" : command) {
                case "seed" -> seed(conn, require(opts, "email"),
                        opts.getOrDefault("display-name", "Admin"),
                        readPassword(opts));
                case "verify" -> verify(conn, require(opts, "email"));
                default -> {
                    System.err.println("unknown command (want seed|verify): " + command);
                    System.exit(2);
                }
            }
        }
    }

    private static String require(Map<String, String> opts, String key) {
        String v = opts.get(key);
        if (v == null || v.isBlank()) {
            System.err.println("missing --" + key);
            System.exit(2);
        }
        return v;
    }

    private static String readPassword(Map<String, String> opts) throws Exception {
        String fromEnv = opts.containsKey("password-env-app")
                ? System.getenv(opts.get("password-env-app")) : null;
        if (fromEnv != null && !fromEnv.isEmpty()) {
            return fromEnv;
        }
        java.util.Scanner scanner = new java.util.Scanner(System.in, java.nio.charset.StandardCharsets.UTF_8);
        System.err.println("reading one line of password from stdin");
        String pw = scanner.hasNextLine() ? scanner.nextLine() : null;
        if (pw == null || pw.isEmpty()) {
            System.err.println("no password on stdin (use --password-env-app VAR to avoid piping)");
            System.exit(2);
        }
        return pw;
    }

    private static void seed(Connection conn, String email, String displayName, String password) throws Exception {
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        try (PreparedStatement exists = conn.prepareStatement(
                "SELECT 1 FROM app_user WHERE email = ?")) {
            exists.setString(1, normalized);
            try (ResultSet rs = exists.executeQuery()) {
                if (rs.next()) {
                    System.out.println("seed skipped (user already exists): " + normalized);
                    return; // never overwrite an existing password
                }
            }
        }
        org.springframework.security.crypto.argon2.Argon2PasswordEncoder encoder =
                org.springframework.security.crypto.argon2.Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        try (PreparedStatement insert = conn.prepareStatement(
                "INSERT INTO app_user (email, password_hash, display_name, created_at) VALUES (?, ?, ?, now())")) {
            insert.setString(1, normalized);
            insert.setString(2, encoder.encode(password));
            insert.setString(3, displayName);
            insert.executeUpdate();
        }
        System.out.println("seeded: " + normalized);
    }

    private static void verify(Connection conn, String email) throws Exception {
        String normalized = email.trim().toLowerCase(java.util.Locale.ROOT);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM app_user WHERE email = ?")) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    System.out.println("verify ok: " + normalized);
                    return;
                }
            }
        }
        System.err.println("verify FAILED: no user " + normalized);
        System.exit(1);
    }
}
