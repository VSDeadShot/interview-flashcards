package dev.vsdeadshot.flashcards.support;

import dev.vsdeadshot.flashcards.service.TokenService;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for tests that need a database.
 *
 * <p>Starts a real PostgreSQL server in-process and points the application at it, so the
 * suite does not depend on a server being installed and running on the machine, and never
 * touches the developer's own {@code flashcards} database.
 *
 * <p>Testcontainers would be the conventional choice; there is no Docker here, so this
 * uses Zonky, which unpacks and runs an actual Postgres binary. That distinction matters:
 * an in-memory stand-in like H2 would quietly accept things real Postgres rejects, and
 * this schema leans on Postgres-specific features — {@code timestamptz}, identity columns,
 * and a partial index.
 *
 * <p>Flyway migrates the fresh database on context startup, so every run also proves the
 * migrations still apply cleanly from nothing. Hibernate then validates the entity
 * mappings against the result.
 *
 * <p>One server is shared by the whole test JVM. Tests must therefore leave the database
 * as they found it — the usual way being {@code @Transactional}, which rolls back.
 */
// Both settings are supplied as inlined properties rather than through @DynamicPropertySource,
// and the distinction is load-bearing. Dynamic sources are added ahead of inlined ones, and a
// base class's are applied *after* a subclass's — so registering the hash below would silently
// overwrite the one that AuthControllerTest, RefreshTokenTest and SignInMalformedHashTest each
// configure for themselves, and their sign-ins would start answering 401 for a reason nothing in
// those files mentions. Inlined here, a subclass that needs its own value simply wins.
//
// The Gemini key is *cleared* rather than left to chance, for the same reason the datasource and
// the passphrase hash are overridden below: a test must not read the developer's real
// configuration, whether to depend on it or to trip over it. Inlined properties outrank the OS
// environment, so an exported FLASHCARDS_GEMINI_API_KEY reaches nothing that extends this class.
// It sits here rather than on the one class that asserts on it because the exposure is wider than
// that assertion: with a key bound, the container wires a real GeminiRestClient into every context
// this base class starts, and anything that ever reached it would spend real quota.
@SpringBootTest(properties = {
        "flashcards.passphrase-hash=" + EmbeddedPostgresTest.TEST_PASSPHRASE_HASH,
        "flashcards.gemini.api-key="
})
public abstract class EmbeddedPostgresTest {

    private static final EmbeddedPostgres POSTGRES = start();

    private static EmbeddedPostgres start() {
        try {
            EmbeddedPostgres postgres = EmbeddedPostgres.builder().start();
            // The JVM exits when the test task finishes; this stops the process leaking.
            Runtime.getRuntime().addShutdownHook(new Thread(() -> stop(postgres), "embedded-pg-stop"));
            return postgres;
        } catch (IOException e) {
            throw new UncheckedIOException("could not start the embedded PostgreSQL server", e);
        }
    }

    private static void stop(EmbeddedPostgres postgres) {
        try {
            postgres.close();
        } catch (IOException e) {
            // Nothing useful to do during shutdown, and throwing here would mask the
            // real result of the test run.
        }
    }

    /**
     * Overrides the datasource for tests only. These take precedence over
     * {@code application.properties}, so its {@code ${FLASHCARDS_DB_PASSWORD}} placeholder
     * is never resolved and the suite runs on a machine where that variable is unset.
     *
     * <p>The URL is assembled from the port rather than taken from a convenience method,
     * because {@code getPort()} is the one accessor whose signature is stable across
     * Zonky versions.
     */
    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> "jdbc:postgresql://localhost:" + POSTGRES.getPort() + "/postgres");
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "postgres");
    }

    /** The passphrase the suite signs in with. Not a secret — it opens nothing real. */
    public static final String TEST_PASSPHRASE = "the passphrase this suite uses";

    /**
     * The hash of {@link #TEST_PASSPHRASE}, at cost 4 rather than the tool's 12.
     *
     * <p>A literal rather than computed, because an annotation argument has to be a compile-time
     * constant and this is consumed by {@code @SpringBootTest} above. Bcrypt carries its own cost
     * factor, so the server verifies this exactly as it would a real one; 4 keeps the suite's
     * sign-ins cheap. Note the bare {@code $} sequences are safe here — Spring only expands
     * {@code ${...}}, which is the half of the shell-mangling problem that does not apply.
     */
    public static final String TEST_PASSPHRASE_HASH =
            "$2a$04$ZKOq13fv4strw3LfpKGAxu.w4pN5lnbx2f/3vQS31jcHYCDsKgiLm";

    public static final String TEST_USER_ID = "test-user";

    /**
     * Supplies the settings production reads from the environment, for the same reason as the
     * datasource above: {@code ./gradlew test} must not need {@code FLASHCARDS_PASSPHRASE_HASH}
     * set on the machine, and must never pick up the developer's real one if it happens to be.
     */
    @DynamicPropertySource
    static void applicationProperties(DynamicPropertyRegistry registry) {
        registry.add("flashcards.user-id", () -> TEST_USER_ID);
    }

    @Autowired
    private TokenService tokenService;

    /**
     * A freshly issued access token, as an {@code Authorization} header value.
     *
     * <p><strong>Minted per call rather than cached.</strong> The obvious optimisation — one
     * token for the whole JVM — is wrong here for a specific reason: controller tests are
     * deliberately not {@code @Transactional}, so their rows are committed, and
     * {@code AuthControllerTest} clears {@code auth_token} in its own cleanup. A cached token
     * would be revoked out from under whichever class ran next, and the failure would look like
     * a flaky ordering bug rather than a shared-fixture one. An insert is cheaper than that.
     */
    protected String bearer() {
        return "Bearer " + tokenService.issue(TEST_USER_ID).accessToken();
    }
}
