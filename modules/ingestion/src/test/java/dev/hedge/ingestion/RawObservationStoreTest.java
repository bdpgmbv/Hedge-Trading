package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.temporal.ObservationKind;
import dev.hedge.persistence.DataSources;
import dev.hedge.ingestion.raw.BatchStatus;
import dev.hedge.ingestion.raw.DuplicateArtifactException;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.raw.StoredBatch;
import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.BatchCompleteness;
import dev.hedge.ingestion.source.DeliveryStyle;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.RawBatch;
import dev.hedge.ingestion.source.RawRecord;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RawObservationStoreTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-10-13T21:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-10-14T02:15:00Z");

    private static final SourceDescriptor CUSTODIAN_A = new SourceDescriptor(
            ProviderId.of("CUSTODIAN_A"),
            Set.of(ObservationKind.POSITION),
            IdentifierKind.CUSIP,
            DeliveryStyle.SNAPSHOT,
            "csv");

    private static DataSource dataSource;
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
    void cleanDatabase() {
        IngestionTestDatabase.clear(dataSource);
        store = new RawObservationStore(dataSource);
        store.registerProvider(CUSTODIAN_A);
    }

    private static RawBatch batchOf(String name, String content, int records) {
        return new RawBatch(
                CUSTODIAN_A,
                Artifact.of(name, content),
                RECEIVED,
                java.util.stream.IntStream.range(0, records)
                        .mapToObj(index -> new RawRecord(
                                index,
                                "ACC-1,037833100," + (100 + index),
                                Optional.of("rec-" + index),
                                Optional.of("1"),
                                EFFECTIVE))
                        .toList(),
                BatchCompleteness.verified(records, records));
    }

    @Test
    @DisplayName("a batch and all its records are stored exactly as received")
    void a_batch_is_stored_verbatim() {
        StoredBatch stored = store.store(batchOf("custodianA_20261013.csv", "a,b,c", 3));

        assertThat(stored.status()).isEqualTo(BatchStatus.ACCEPTED);
        assertThat(stored.parsedCount()).isEqualTo(3);

        List<StoredObservation> observations = store.observationsIn(stored.id());
        assertThat(observations).hasSize(3);
        assertThat(observations.get(0).payload()).isEqualTo("ACC-1,037833100,100");
        assertThat(observations.get(0).effectiveAt()).isEqualTo(EFFECTIVE);
        assertThat(observations.get(0).receivedAt()).isEqualTo(RECEIVED);
    }

    @Test
    @DisplayName("the same bytes delivered twice are refused, however the file is named")
    void the_same_bytes_are_never_ingested_twice() {
        store.store(batchOf("custodianA_20261013.csv", "identical content", 2));

        assertThatThrownBy(() -> store.store(batchOf("custodianA_RETRY.csv", "identical content", 2)))
                .isInstanceOf(DuplicateArtifactException.class)
                .hasMessageContaining("already delivered");
    }

    @Test
    @DisplayName("a refused duplicate leaves no trace of its records behind")
    void a_refused_duplicate_writes_nothing() {
        StoredBatch first = store.store(batchOf("a.csv", "same", 2));

        assertThatThrownBy(() -> store.store(batchOf("b.csv", "same", 5)))
                .isInstanceOf(DuplicateArtifactException.class);

        assertThat(store.observationsIn(first.id())).hasSize(2);
        assertThat(store.batchesInReceiptOrder()).hasSize(1);
    }

    @Test
    @DisplayName("different bytes from the same provider are both kept")
    void different_bytes_are_both_kept() {
        store.store(batchOf("monday.csv", "monday content", 2));
        store.store(batchOf("tuesday.csv", "tuesday content", 3));

        assertThat(store.batchesInReceiptOrder()).hasSize(2);
    }

    @Test
    @DisplayName("a truncated batch is stored as evidence but marked unsafe to interpret")
    void a_truncated_batch_is_marked_incomplete() {
        RawBatch truncated = new RawBatch(
                CUSTODIAN_A,
                Artifact.of("cut-short.csv", "two of the four rows arrived"),
                RECEIVED,
                List.of(
                        new RawRecord(0, "row one", Optional.empty(), Optional.empty(), EFFECTIVE),
                        new RawRecord(1, "row two", Optional.empty(), Optional.empty(), EFFECTIVE)),
                BatchCompleteness.verified(4, 2));

        StoredBatch stored = store.store(truncated);

        assertThat(stored.status()).isEqualTo(BatchStatus.INCOMPLETE);
        assertThat(stored.problem()).isPresent();
        assertThat(stored.problem().orElseThrow()).contains("declared 4");
        assertThat(store.observationsIn(stored.id())).hasSize(2);
    }

    @Test
    @DisplayName("ids come from the content, so replaying the same artifact rebuilds the same ids")
    void ids_are_deterministic() {
        StoredBatch first = store.store(batchOf("x.csv", "deterministic", 2));
        IngestionTestDatabase.clear(dataSource);
        store.registerProvider(CUSTODIAN_A);
        StoredBatch again = store.store(batchOf("x.csv", "deterministic", 2));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(store.observationsIn(again.id()).get(0).id())
                .isEqualTo(store.observationsIn(again.id()).get(0).id());
    }

    @Test
    @DisplayName("an unknown batch is absent rather than an error")
    void an_unknown_batch_is_empty() {
        assertThat(store.findBatch(BatchId.of("NOPE"))).isEmpty();
    }
}
