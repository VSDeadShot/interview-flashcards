package dev.vsdeadshot.flashcards.tools;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * Turns a passphrase into the bcrypt hash that {@code FLASHCARDS_PASSPHRASE_HASH} holds.
 *
 * <p>In the test source set on purpose: it must never be inside the jar that gets deployed. A
 * running server carrying a tool that mints password hashes is a liability with no upside, and
 * this runs once in the lifetime of the application.
 *
 * <p>Reads standard input rather than an argument. An argument is written into shell history and
 * is readable from the process list by anything else on the machine for as long as the command
 * runs, neither of which is where a passphrase should end up.
 *
 * <p>Run it with {@code ./gradlew -q --console=plain hashPassphrase}. Note that Gradle gives the
 * task no terminal, so what is typed is echoed and stays in the scrollback -- fine for a one-off
 * on a machine you own, worth knowing before doing it over somebody's shoulder.
 */
public final class PassphraseHashTool {

    /**
     * Twelve rather than the default ten. The cost is paid once per sign-in and never on an
     * authenticated request, so the usual argument against a high factor does not apply here.
     *
     * <p>It is set only in this tool, and that is not an oversight: a bcrypt hash carries its own
     * cost factor, so verification uses whatever the stored hash was generated with. The encoder
     * on the server side never has to be told.
     */
    private static final int COST = 12;

    private PassphraseHashTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 0) {
            System.err.println("Pass the passphrase on standard input, not as an argument -- an "
                    + "argument is kept in shell history and is visible in the process list.");
            System.exit(2);
        }

        System.out.println("Passphrase (will be echoed):");
        BufferedReader in =
                new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        String passphrase = in.readLine();

        if (passphrase == null || passphrase.isBlank()) {
            System.err.println("Nothing read. Aborting rather than hashing an empty passphrase.");
            System.exit(2);
        }

        System.out.println();
        System.out.println("Set this as FLASHCARDS_PASSPHRASE_HASH:");
        System.out.println(new BCryptPasswordEncoder(COST).encode(passphrase));
    }
}
