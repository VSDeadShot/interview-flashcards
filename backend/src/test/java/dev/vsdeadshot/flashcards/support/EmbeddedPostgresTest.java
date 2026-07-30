package dev.vsdeadshot.flashcards.support;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.io.UncheckedIOException;
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
@SpringBootTest
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
}
