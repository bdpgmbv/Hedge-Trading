package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.ingestion.IngestionPipeline.Outcome;
import dev.hedge.ingestion.legacy.LegacyOverwriteIngester;
import dev.hedge.ingestion.provider.CustodianACsvSource;
import dev.hedge.ingestion.raw.BatchStatus;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.persistence.DataSources;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The same files through both designs, side by side. Each test states one question the business
 * asks, and shows that the old system cannot answer it while the new one can.
 */
class LegacyVersusEvidenceTest {

    private static final String MONDAY = """
            HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
            POSITION,ACC-00001,037833100,1500.00000000
            POSITION,ACC-00001,594918104,2300.00000000
            TRAILER,2
            """;

    /** The same business day, corrected: the first holding was actually 900, not 1500. */
    private static final String MONDAY_CORRECTED = """
            HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
            POSITION,ACC-00001,037833100,900.00000000
            POSITION,ACC-00001,594918104,2300.00000000
            TRAILER,2
            """;

    private static DataSource dataSource;
    private IngestionPipeline pipeline;
    private LegacyOverwriteIngester legacy;
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
    void clean() {
        IngestionTestDatabase.clear(dataSource);
        clearLegacy();
        store = new RawObservationStore(dataSource);
        pipeline = new IngestionPipeline(
                store, Clock.fixed(Instant.parse("2026-10-14T02:15:00Z"), ZoneOffset.UTC));
        legacy = new LegacyOverwriteIngester(dataSource);
    }

    private static void clearLegacy() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table legacy_positions");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear legacy positions", failure);
        }
    }

    @Test
    @DisplayName("a correction: the old system destroys Monday's original, the new one keeps both")
    void a_correction_destroys_history_only_in_the_old_system() {
        legacy.ingest(MONDAY);
        assertThat(legacy.quantityOf("ACC-00001", "037833100")).contains("1500");
        legacy.ingest(MONDAY_CORRECTED);

        // The old system can only say what it believes now. The original 1500 is simply gone.
        assertThat(legacy.quantityOf("ACC-00001", "037833100")).contains("900");

        pipeline.accept(new CustodianACsvSource(), Artifact.of("monday.csv", MONDAY));
        pipeline.accept(new CustodianACsvSource(), Artifact.of("monday-corrected.csv", MONDAY_CORRECTED));

        // Both deliveries survive, so both the original and the correction remain answerable.
        assertThat(store.batchesInReceiptOrder()).hasSize(2);
        String firstDelivery = store.observationsIn(store.batchesInReceiptOrder().get(0)).get(0).payload();
        String secondDelivery = store.observationsIn(store.batchesInReceiptOrder().get(1)).get(0).payload();
        assertThat(firstDelivery).contains("1500.00000000");
        assertThat(secondDelivery).contains("900.00000000");
    }

    @Test
    @DisplayName("a retransmission: the old system reprocesses it, the new one refuses it")
    void a_duplicate_is_only_caught_by_the_new_system() {
        legacy.ingest(MONDAY);
        legacy.ingest(MONDAY);
        // On a snapshot the old system survives by luck; the same file on an incremental feed
        // would have doubled every holding, with nothing recording that it happened twice.
        assertThat(legacy.rowCount()).isEqualTo(2);

        pipeline.accept(new CustodianACsvSource(), Artifact.of("monday.csv", MONDAY));
        Outcome second =
                pipeline.accept(new CustodianACsvSource(), Artifact.of("monday-RETRY.csv", MONDAY));

        assertThat(second).isInstanceOf(Outcome.AlreadySeen.class);
        assertThat(store.batchesInReceiptOrder()).hasSize(1);
    }

    @Test
    @DisplayName("a row nobody can parse: the old system drops it silently, the new one reports it")
    void an_unparseable_row_is_only_visible_in_the_new_system() {
        String withJunk = """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                WHAT_IS_THIS,ACC-00001,999
                TRAILER,2
                """;

        int written = legacy.ingest(withJunk);
        assertThat(written).isEqualTo(1); // the junk row vanished and nothing said so

        Outcome outcome = pipeline.accept(new CustodianACsvSource(), Artifact.of("junk.csv", withJunk));

        assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
        assertThat(((Outcome.Rejected) outcome).problem()).contains("WHAT_IS_THIS");
    }

    @Test
    @DisplayName("a custodian quoting a price: the old system stores nothing, the new one quarantines")
    void market_data_from_a_custodian_is_quarantined() {
        String overreach = """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                PRICE,037833100,USD,184.52
                TRAILER,1
                """;

        Outcome outcome = pipeline.accept(new CustodianACsvSource(), Artifact.of("over.csv", overreach));

        assertThat(outcome).isInstanceOf(Outcome.Rejected.class);
        assertThat(store.findBatch(((Outcome.Rejected) outcome).batch().id()).orElseThrow().status())
                .isEqualTo(BatchStatus.QUARANTINED);
    }

    @Test
    @DisplayName("every stored number can name the file it came from; the old system records none")
    void provenance_exists_only_in_the_new_system() {
        pipeline.accept(new CustodianACsvSource(), Artifact.of("monday.csv", MONDAY));

        var batch = store.findBatch(store.batchesInReceiptOrder().get(0)).orElseThrow();

        assertThat(batch.artifactName()).isEqualTo("monday.csv");
        assertThat(batch.provider().value()).isEqualTo("CUSTODIAN_A");
        assertThat(batch.artifactSha256()).hasSize(64);
        assertThat(store.observationsIn(batch.id())).hasSize(2);
    }
}
