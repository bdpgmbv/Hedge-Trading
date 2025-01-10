package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.ingestion.IngestionPipeline.Outcome;
import dev.hedge.ingestion.provider.CustodianACsvSource;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.source.ObservationSource;
import dev.hedge.ingestion.transport.DropDirectoryWatcher;
import dev.hedge.persistence.DataSources;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DropDirectoryWatcherTest {

    private static final String MONDAY = """
            HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
            POSITION,ACC-00001,037833100,1500.00000000
            TRAILER,1
            """;

    private static DataSource dataSource;

    @TempDir
    Path root;

    private Path drop;
    private Path archive;
    private DropDirectoryWatcher watcher;
    private RawObservationStore store;

    @BeforeAll
    static void startDatabase() {
        dataSource = IngestionTestDatabase.start();
    }

    @AfterAll
    static void stopDatabase() {
        DataSources.close(dataSource);
    }

    @BeforeEach
    void setUp() throws IOException {
        IngestionTestDatabase.clear(dataSource);
        drop = Files.createDirectories(root.resolve("drop"));
        archive = Files.createDirectories(root.resolve("archive"));
        store = new RawObservationStore(dataSource);
        Map<String, ObservationSource> sources = Map.of("CUSTODIAN_A", new CustodianACsvSource());
        watcher = new DropDirectoryWatcher(
                drop,
                archive,
                sources,
                new IngestionPipeline(
                        store, Clock.fixed(Instant.parse("2026-10-14T02:15:00Z"), ZoneOffset.UTC)));
    }

    private Path deliver(String name, String content, boolean finished) throws IOException {
        Path file = Files.writeString(drop.resolve(name), content);
        if (finished) {
            Files.writeString(drop.resolve(name + ".done"), "");
        }
        return file;
    }

    @Test
    @DisplayName("a file still being written is left alone until its done marker appears")
    void a_file_without_a_done_marker_is_not_read() throws IOException {
        deliver("CUSTODIAN_A_20261013.csv", MONDAY, false);

        assertThat(watcher.sweep()).isEmpty();
        assertThat(store.batchesInReceiptOrder()).isEmpty();
        assertThat(Files.exists(drop.resolve("CUSTODIAN_A_20261013.csv"))).isTrue();
    }

    @Test
    @DisplayName("once the done marker appears the file is ingested and archived, never deleted")
    void a_finished_file_is_ingested_and_archived() throws IOException {
        deliver("CUSTODIAN_A_20261013.csv", MONDAY, true);

        List<DropDirectoryWatcher.Swept> swept = watcher.sweep();

        assertThat(swept).hasSize(1);
        assertThat(swept.get(0).outcome()).isInstanceOf(Outcome.Ingested.class);
        assertThat(Files.exists(archive.resolve("CUSTODIAN_A_20261013.csv"))).isTrue();
        assertThat(Files.exists(drop.resolve("CUSTODIAN_A_20261013.csv"))).isFalse();
        assertThat(Files.exists(drop.resolve("CUSTODIAN_A_20261013.csv.done"))).isFalse();
    }

    @Test
    @DisplayName("a second sweep finds nothing, because the file was claimed by the first")
    void a_file_is_only_processed_once() throws IOException {
        deliver("CUSTODIAN_A_20261013.csv", MONDAY, true);

        watcher.sweep();

        assertThat(watcher.sweep()).isEmpty();
        assertThat(store.batchesInReceiptOrder()).hasSize(1);
    }

    @Test
    @DisplayName("a file from a provider we do not know is left where it is")
    void an_unknown_provider_is_left_alone() throws IOException {
        deliver("SOMEONE_ELSE_20261013.csv", MONDAY, true);

        assertThat(watcher.sweep()).isEmpty();
        assertThat(Files.exists(drop.resolve("SOMEONE_ELSE_20261013.csv"))).isTrue();
    }

    @Test
    @DisplayName("deliveries are processed oldest first, so an incremental feed stays in order")
    void deliveries_are_processed_in_arrival_order() throws IOException {
        Path first = deliver("CUSTODIAN_A_mon.csv", MONDAY, true);
        Path second = deliver("CUSTODIAN_A_tue.csv", MONDAY.replace("1500", "1600"), true);
        Files.setLastModifiedTime(first, java.nio.file.attribute.FileTime.fromMillis(1_000_000));
        Files.setLastModifiedTime(second, java.nio.file.attribute.FileTime.fromMillis(2_000_000));

        List<DropDirectoryWatcher.Swept> swept = watcher.sweep();

        assertThat(swept).hasSize(2);
        assertThat(swept.get(0).fileName()).isEqualTo("CUSTODIAN_A_mon.csv");
        assertThat(swept.get(1).fileName()).isEqualTo("CUSTODIAN_A_tue.csv");
    }

    @Test
    @DisplayName("the same bytes redelivered under a new name are recognised and refused")
    void a_retransmission_is_refused() throws IOException {
        deliver("CUSTODIAN_A_20261013.csv", MONDAY, true);
        watcher.sweep();
        deliver("CUSTODIAN_A_20261013_RETRY.csv", MONDAY, true);

        List<DropDirectoryWatcher.Swept> swept = watcher.sweep();

        assertThat(swept).hasSize(1);
        assertThat(swept.get(0).outcome()).isInstanceOf(Outcome.AlreadySeen.class);
        assertThat(store.batchesInReceiptOrder()).hasSize(1);
        // Even a refused delivery is archived; the bytes stay available for investigation.
        assertThat(Files.exists(archive.resolve("CUSTODIAN_A_20261013_RETRY.csv"))).isTrue();
    }
}
