package deploy.tools;

import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;

import java.io.Console;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

/**
 * DEPLOY-BUNDLE operator tool: prints an Argon2 hash for the EXACT encoder
 * the server uses ({@code Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8()},
 * same as com.keplura.server.identity.PasswordHasher) for a password read
 * from STDIN (never argv -- argv leaks into ps output and shell history).
 *
 * Use it together with deploy/tools/AccountTool.java seed (the documented
 * first-login path for a prod deployment, which intentionally has NO dev
 * seeder -- see DevAccountSeeder javadoc).
 *
 * Build & run: see deploy/README.md (javac/java one-liners against the fat
 * jar's BOOT-INF/lib -- no repo build needed on the target machine beyond a
 * JDK, which the container deploy does not even need).
 */
public final class PasswordHashTool {

    private PasswordHashTool() {
    }

    public static void main(String[] args) {
        String password;
        try (Scanner scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            Console console = System.console();
            if (console != null) {
                char[] chars = console.readPassword("password (input hidden): ");
                password = chars == null ? null : new String(chars);
            } else {
                System.err.println("reading one line of password from stdin");
                password = scanner.hasNextLine() ? scanner.nextLine() : null;
            }
        }
        if (password == null || password.isEmpty()) {
            System.err.println("usage: pipe the password on stdin (never as an argument)");
            System.exit(2);
        }
        Argon2PasswordEncoder encoder = Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
        System.out.println(encoder.encode(password));
    }
}
