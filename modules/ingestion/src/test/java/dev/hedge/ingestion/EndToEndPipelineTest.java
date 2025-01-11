package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.ingestion.IngestionPipeline.Outcome;
import dev.hedge.ingestion.canonical.CanonicalPositionStore;
import dev.hedge.ingestion.canonical.CanonicalProjector;
import dev.hedge.ingestion.identity.AccountMappingRegistry;
import dev.hedge.ingestion.identity.SecurityIdentifierRegistry;
import dev.hedge.ingestion.identity.UnresolvedIdentifierLog;
import dev.hedge.ingestion.lineage.LineageQuery;
import dev.hedge.ingestion.lineage.LineageTrail;
import dev.hedge.ingestion.normalize.DelimitedPositionNormalizer;
import dev.hedge.ingestion.normalize.NormalizedPosition;
import dev.hedge.ingestion.normalize.Normalizer;
import dev.hedge.ingestion.provider.CustodianACsvSource;
import dev.hedge.ingestion.provider.CustodianBJsonSource;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.ingestion.raw.StoredBatch;
import dev.hedge.ingestion.replay.ReplayEngine;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.ObservationSource;
import dev.hedge.ingestion.source.SourceDescriptor;
import dev.hedge.persistence.DataSources;
import dev.hedge.reconciliation.BreakType;
import dev.hedge.reconciliation.ReconciliationEngine;
import dev.hedge.reconciliation.Tolerance;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

/**
 * Layers 42, 43 and 44 end to end: the six things that go wrong with provider data, the trail back
 * to the bytes, and the proof that history can be rebuilt from evidence alone.
 */
class EndToEndPipelineTest {

    private static final Instant TUESDAY = Instant.parse("2026-10-14T02:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-10-15T02:00:00Z");
    private static final Instant MONDAY_CLOSE = Instant.parse("2026-10-13T21:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-20T00:00:00Z");

    private static final ProviderId A = ProviderId.of("CUSTODIAN_A");
    private static final AccountId ACCOUNT = AccountId.of("ACC-REAL-1");
    private static final SecurityId APPLE = SecurityId.of("SEC-APPLE");
    private static final SecurityId MSFT = SecurityId.of("SEC-MSFT");

    private static final String MONDAY = """
            HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
            POSITION,ACC-00001,037833100,1500.00000000
            POSITION,ACC-00001,594918104,2300.00000000
            TRAILER,2
            """;

    private static DataSource dataSource;
    private RawObservationStore raw;
    private CanonicalPositionStore canonical;
    private CanonicalProjector projector;
    private SecurityIdentifierRegistry securities;
    private AccountMappingRegistry accounts;
    private LineageQuery lineage;
    private final CustodianACsvSource custodianA = new CustodianACsvSource();
    private final Normalizer<NormalizedPosition> normalizer = new DelimitedPositionNormalizer();

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
        truncateAll();
        raw = new RawObservationStore(dataSource);
        canonical = new CanonicalPositionStore(dataSource);
        securities = new SecurityIdentifierRegistry(dataSource);
        accounts = new AccountMappingRegistry(dataSource);
        lineage = new LineageQuery(dataSource);
        projector = new CanonicalProjector(
                securities, accounts, new UnresolvedIdentifierLog(dataSource));

        securities.map(IdentifierKind.CUSIP, "037833100", APPLE, "reference data");
        securities.map(IdentifierKind.CUSIP, "594918104", MSFT, "reference data");
        accounts.map(A, "ACC-00001", ACCOUNT);
    }

    private static void truncateAll() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table canonical_positions, unresolved_identifiers,"
                    + " raw_observations, ingest_batches, providers, security_identifiers,"
                    + " account_mappings cascade");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear the database", failure);
        }
    }

    private StoredBatch ingest(ObservationSource source, String name, String content, Instant at) {
        Outcome outcome = new IngestionPipeline(raw, Clock.fixed(at, ZoneOffset.UTC))
                .accept(source, Artifact.of(name, content));
        return switch (outcome) {
            case Outcome.Ingested ingested -> ingested.batch();
            case Outcome.Rejected rejected -> rejected.batch();
            case Outcome.AlreadySeen seen -> null;
        };
    }

    private void ingestAndProject(String name, String content, Instant at) {
        StoredBatch batch = ingest(custodianA, name, content, at);
        if (batch != null) {
            projector.projectAndStore(raw, canonical, batch.id(), custodianA.descriptor(), normalizer);
        }
    }

    // ---------- Layer 42: the six things that go wrong ----------

    @Test
    @DisplayName("1 duplicate batch: redelivered bytes have no second economic effect")
    void a_duplicate_batch_changes_nothing() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        int afterFirst = canonical.count();

        ingestAndProject("mon-RETRY.csv", MONDAY, WEDNESDAY);

        assertThat(canonical.count()).isEqualTo(afterFirst);
    }

    @Test
    @DisplayName("2 late correction: Monday is restated and both versions remain answerable")
    void a_late_correction_keeps_both_versions() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        ingestAndProject("mon-fix.csv", MONDAY.replace("1500.00000000", "900.00000000"), WEDNESDAY);

        assertThat(quantity(canonical.asOf(A, MONDAY_CLOSE, NOW))).isEqualTo("900");
        assertThat(quantity(canonical.asOf(A, MONDAY_CLOSE, TUESDAY.plusSeconds(3600))))
                .isEqualTo("1500");
    }

    @Test
    @DisplayName("3 unknown security: it becomes a visible gap, never a silent omission")
    void an_unknown_security_is_reported() {
        UnresolvedIdentifierLog unresolved = new UnresolvedIdentifierLog(dataSource);
        String withStranger = """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                POSITION,ACC-00001,999999999,42.00000000
                TRAILER,2
                """;

        ingestAndProject("stranger.csv", withStranger, TUESDAY);

        assertThat(canonical.count()).isEqualTo(1);
        assertThat(unresolved.outstanding()).hasSize(1);
        assertThat(unresolved.outstanding().get(0).identifier()).isEqualTo("999999999");
        assertThat(unresolved.outstanding().get(0).occurrences()).isEqualTo(1);
    }

    @Test
    @DisplayName("3b the same unknown identifier seen again is counted, not duplicated")
    void repeated_unknowns_are_counted() {
        UnresolvedIdentifierLog unresolved = new UnresolvedIdentifierLog(dataSource);
        String withStranger = """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,999999999,42.00000000
                TRAILER,1
                """;

        ingestAndProject("s1.csv", withStranger, TUESDAY);
        ingestAndProject("s2.csv", withStranger.replace("42.00000000", "43.00000000"), WEDNESDAY);

        assertThat(unresolved.outstanding()).hasSize(1);
        assertThat(unresolved.outstanding().get(0).occurrences()).isEqualTo(2);
    }

    @Test
    @DisplayName("4 conflicting providers: a disagreement becomes a classified break")
    void two_providers_that_disagree_produce_a_break() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);

        List<Observation<Position>> ours = canonical.asOf(A, MONDAY_CLOSE, NOW);
        List<Observation<Position>> theirs = List.of(ours.get(0)); // they only know one of the two

        var run = new ReconciliationEngine()
                .reconcile("CUSTODIAN_A", ours, "CUSTODIAN_B", theirs, Tolerance.exact());

        assertThat(run.count(BreakType.MATCH)).isEqualTo(1);
        assertThat(run.count(BreakType.MISSING)).isEqualTo(1);
    }

    @Test
    @DisplayName("5 missing trailer: the batch is stored as evidence but not interpreted")
    void a_missing_trailer_stops_interpretation() {
        StoredBatch batch = ingest(custodianA, "notrailer.csv", """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                """, TUESDAY);

        assertThat(batch.problem().orElseThrow()).contains("no TRAILER");
        assertThat(raw.observationsIn(batch.id())).hasSize(1); // the evidence is kept
        assertThat(canonical.count()).isZero(); // but nothing was promoted to canonical
    }

    @Test
    @DisplayName("6 malformed input: the offending row is named rather than skipped")
    void malformed_input_is_named() {
        StoredBatch batch = ingest(custodianA, "junk.csv", """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                WHAT_IS_THIS,ACC-00001,999
                TRAILER,1
                """, TUESDAY);

        assertThat(batch.problem().orElseThrow()).contains("WHAT_IS_THIS");
    }

    @Test
    @DisplayName("a json custodian flows through the same pipeline as the csv one")
    void a_second_format_needs_no_second_pipeline() {
        securities.map(IdentifierKind.ISIN, "US0378331005", APPLE, "reference data");
        accounts.map(ProviderId.of("CUSTODIAN_B"), "CUST-B-99", ACCOUNT);
        CustodianBJsonSource custodianB = new CustodianBJsonSource();

        StoredBatch batch = ingest(custodianB, "b.json", """
                {"effectiveAt":"2026-10-13T21:00:00Z","changes":[
                 {"recordId":"B-1","version":1,"account":"CUST-B-99",
                  "isin":"US0378331005","quantity":"1500"}]}
                """, TUESDAY);
        projector.projectAndStore(raw, canonical, batch.id(), custodianB.descriptor(), normalizer);

        var fromB = canonical.asOf(ProviderId.of("CUSTODIAN_B"), MONDAY_CLOSE, NOW);
        assertThat(fromB).hasSize(1);
        assertThat(fromB.get(0).value().securityId()).isEqualTo(APPLE);
    }

    // ---------- Layer 43: lineage ----------

    @Test
    @DisplayName("any number traces back to the file, the bytes and the row that produced it")
    void every_number_traces_to_its_evidence() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);

        List<LineageTrail> trails = lineage.forHolding(ACCOUNT, APPLE, MONDAY_CLOSE, NOW);

        assertThat(trails).hasSize(1);
        LineageTrail trail = trails.get(0);
        assertThat(trail.artifactName()).isEqualTo("mon.csv");
        assertThat(trail.provider()).isEqualTo(A);
        assertThat(trail.artifactSha256()).hasSize(64);
        assertThat(trail.rawPayload()).isEqualTo("ACC-00001,037833100,1500.00000000");
        assertThat(trail.normalizationVersion()).isEqualTo("position-delimited-v1");
        assertThat(trail.describe()).contains("mon.csv").contains("position-delimited-v1");
    }

    @Test
    @DisplayName("a corrected holding shows both statements in its trail, newest first")
    void a_correction_is_visible_in_the_trail() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        ingestAndProject("fix.csv", MONDAY.replace("1500.00000000", "900.00000000"), WEDNESDAY);

        List<LineageTrail> trails = lineage.forHolding(ACCOUNT, APPLE, MONDAY_CLOSE, NOW);

        assertThat(trails).hasSize(2);
        assertThat(trails.get(0).rawPayload()).contains("900");
        assertThat(trails.get(1).rawPayload()).contains("1500");
    }

    @Test
    @DisplayName("every canonical value is traceable: coverage is total")
    void lineage_coverage_is_complete() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        ingestAndProject("fix.csv", MONDAY.replace("1500.00000000", "900.00000000"), WEDNESDAY);

        assertThat(lineage.lineageCoverage()).isEqualTo(1.0);
    }

    // ---------- Layer 44: deterministic reconstruction ----------

    @Test
    @DisplayName("replaying the evidence reproduces the live state exactly")
    void live_state_equals_replay_state() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        ingestAndProject("fix.csv", MONDAY.replace("1500.00000000", "900.00000000"), WEDNESDAY);
        ingestAndProject("tue.csv", MONDAY
                .replace("2026-10-13", "2026-10-14")
                .replace("2300.00000000", "2500.00000000"), WEDNESDAY);

        List<Observation<Position>> live = canonical.asOf(A, Instant.parse("2026-10-14T21:00:00Z"), NOW);

        var replayed = new ReplayEngine(raw, projector).replayAsOf(
                Map.of(A.value(), custodianA.descriptor()),
                Map.of(A.value(), normalizer),
                Instant.parse("2026-10-14T21:00:00Z"),
                NOW);

        // The invariant is checked by reconciling the two: zero breaks, or the system cannot
        // explain its own past.
        var run = new ReconciliationEngine()
                .reconcile("LIVE", live, "REPLAY", replayed.positions(), Tolerance.exact());

        assertThat(run.needingAttention()).isEmpty();
        assertThat(run.count(BreakType.MATCH)).isEqualTo(live.size());
        assertThat(replayed.positions()).hasSameSizeAs(live);
    }

    @Test
    @DisplayName("replay at an earlier knowledge time reproduces what was believed then")
    void replay_reproduces_a_past_belief() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        ingestAndProject("fix.csv", MONDAY.replace("1500.00000000", "900.00000000"), WEDNESDAY);

        var replayed = new ReplayEngine(raw, projector).replayAsOf(
                Map.of(A.value(), custodianA.descriptor()),
                Map.of(A.value(), normalizer),
                MONDAY_CLOSE,
                TUESDAY.plusSeconds(3600));

        assertThat(quantity(replayed.positions())).isEqualTo("1500");
    }

    @Test
    @DisplayName("losing the identity mapping makes replay diverge, and the divergence is visible")
    void a_broken_mapping_makes_replay_diverge() {
        ingestAndProject("mon.csv", MONDAY, TUESDAY);
        List<Observation<Position>> live = canonical.asOf(A, MONDAY_CLOSE, NOW);

        // Someone deletes a reference data mapping. Replay can no longer resolve that security.
        deleteMapping("037833100");

        var replayed = new ReplayEngine(raw, projector).replayAsOf(
                Map.of(A.value(), custodianA.descriptor()),
                Map.of(A.value(), normalizer),
                MONDAY_CLOSE,
                NOW);

        var run = new ReconciliationEngine()
                .reconcile("LIVE", live, "REPLAY", replayed.positions(), Tolerance.exact());

        assertThat(run.needingAttention()).hasSize(1);
        assertThat(run.count(BreakType.MISSING)).isEqualTo(1);
        // And lineage still explains it: the evidence is there, the mapping is not.
        assertThat(lineage.forHolding(ACCOUNT, APPLE, MONDAY_CLOSE, NOW)).hasSize(1);
    }

    private static void deleteMapping(String identifier) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(
                    "delete from security_identifiers where identifier_value = '" + identifier + "'");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not delete the mapping", failure);
        }
    }

    private static String quantity(List<Observation<Position>> positions) {
        return positions.stream()
                .filter(observation -> observation.value().securityId().equals(APPLE))
                .findFirst()
                .map(observation -> observation.value().quantity().value().toPlainString())
                .orElse("none");
    }
}
