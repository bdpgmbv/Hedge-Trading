package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.domain.temporal.Provenance;
import dev.hedge.ingestion.canonical.CanonicalPositionStore;
import dev.hedge.ingestion.raw.RawObservationStore;
import dev.hedge.persistence.DataSources;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Layer 40. The scenario is the one every reconciliation platform must survive: a custodian sends
 * a correction for a past business day, after a report on that day has already gone out.
 */
class TemporalSemanticsTest {

    private static final ProviderId CUSTODIAN_A = ProviderId.of("CUSTODIAN_A");
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");
    private static final SecurityId APPLE = SecurityId.of("SEC-APPLE");

    private static final Instant MONDAY_CLOSE = Instant.parse("2026-10-13T21:00:00Z");
    private static final Instant TUESDAY_MORNING = Instant.parse("2026-10-14T02:00:00Z");
    private static final Instant TUESDAY_NOON = Instant.parse("2026-10-14T12:00:00Z");
    private static final Instant WEDNESDAY = Instant.parse("2026-10-15T02:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2026-10-16T02:00:00Z");

    private static DataSource dataSource;
    private CanonicalPositionStore positions;

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
        clearCanonical();
        positions = new CanonicalPositionStore(dataSource);
    }

    private static void clearCanonical() {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table canonical_positions cascade");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear canonical positions", failure);
        }
    }

    /** Canonical rows reference raw observations, so the evidence has to exist first. */
    private void seedObservation(String observationId) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("insert into providers (provider_id, display_name, identifier_kind,"
                    + " delivery_style, format, provides) values ('CUSTODIAN_A','A','CUSIP',"
                    + "'SNAPSHOT','csv','POSITION') on conflict do nothing");
            statement.execute("insert into ingest_batches (batch_id, provider_id, artifact_name,"
                    + " artifact_sha256, artifact_bytes, received_at, parsed_count, status)"
                    + " values ('B-" + observationId + "','CUSTODIAN_A','f.csv',"
                    + "'" + observationId.hashCode() + "0000000000000000000000000000000000000000000000000000000"
                    .substring(0, 64 - String.valueOf(observationId.hashCode()).length()) + "',"
                    + "1, now(), 1, 'ACCEPTED') on conflict do nothing");
            statement.execute("insert into raw_observations (observation_id, batch_id,"
                    + " sequence_in_batch, effective_at, received_at, payload)"
                    + " values ('" + observationId + "','B-" + observationId + "',0, now(), now(), 'x')"
                    + " on conflict do nothing");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not seed observation " + observationId, failure);
        }
    }

    private void state(String observationId, String quantity, Instant effectiveAt, Instant receivedAt) {
        seedObservation(observationId);
        positions.append(CUSTODIAN_A, new Observation<>(
                new Position(ACCOUNT, APPLE, Quantity.of(quantity)),
                effectiveAt,
                receivedAt,
                new Provenance(CUSTODIAN_A, ObservationId.of(observationId), "position-delimited-v1")));
    }

    @Test
    @DisplayName("a correction for Monday does not disturb what we knew on Tuesday")
    void the_past_stays_reproducible_after_a_correction() {
        state("OBS-MON", "1500", MONDAY_CLOSE, TUESDAY_MORNING);
        state("OBS-FIX", "900", MONDAY_CLOSE, WEDNESDAY);

        // What was economically true on Monday, as best we know now
        assertThat(quantityFrom(positions.asOf(CUSTODIAN_A, MONDAY_CLOSE, THURSDAY))).isEqualTo("900");

        // What we believed about Monday on Tuesday, before the correction arrived
        assertThat(quantityFrom(positions.asOf(CUSTODIAN_A, MONDAY_CLOSE, TUESDAY_NOON))).isEqualTo("1500");
    }

    @Test
    @DisplayName("nothing is overwritten: both statements are still on record")
    void both_statements_survive() {
        state("OBS-MON", "1500", MONDAY_CLOSE, TUESDAY_MORNING);
        state("OBS-FIX", "900", MONDAY_CLOSE, WEDNESDAY);

        List<Observation<Position>> history = positions.historyOf(CUSTODIAN_A, ACCOUNT, APPLE);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).value().quantity()).isEqualTo(Quantity.of("900"));
        assertThat(history.get(1).value().quantity()).isEqualTo(Quantity.of("1500"));
        assertThat(positions.count()).isEqualTo(2);
    }

    @Test
    @DisplayName("a value we had not yet been told is invisible at that knowledge time")
    void the_future_is_not_visible_in_the_past() {
        state("OBS-FIX", "900", MONDAY_CLOSE, WEDNESDAY);

        assertThat(positions.asOf(CUSTODIAN_A, MONDAY_CLOSE, TUESDAY_NOON)).isEmpty();
        assertThat(positions.asOf(CUSTODIAN_A, MONDAY_CLOSE, THURSDAY)).hasSize(1);
    }

    @Test
    @DisplayName("a later business day does not leak into an earlier one")
    void a_later_effective_date_is_not_applied_early() {
        state("OBS-MON", "1500", MONDAY_CLOSE, TUESDAY_MORNING);
        state("OBS-TUE", "2000", Instant.parse("2026-10-14T21:00:00Z"), WEDNESDAY);

        assertThat(quantityFrom(positions.asOf(CUSTODIAN_A, MONDAY_CLOSE, THURSDAY))).isEqualTo("1500");
        assertThat(quantityFrom(positions.asOf(
                        CUSTODIAN_A, Instant.parse("2026-10-14T21:00:00Z"), THURSDAY)))
                .isEqualTo("2000");
    }

    @Test
    @DisplayName("every answer names the observation it came from")
    void every_answer_carries_its_provenance() {
        state("OBS-MON", "1500", MONDAY_CLOSE, TUESDAY_MORNING);

        Observation<Position> answer = positions.asOf(CUSTODIAN_A, MONDAY_CLOSE, THURSDAY).get(0);

        assertThat(answer.from().observation()).isEqualTo(ObservationId.of("OBS-MON"));
        assertThat(answer.from().normalizationVersion()).isEqualTo("position-delimited-v1");
        assertThat(answer.wasKnownAt(TUESDAY_NOON)).isTrue();
        assertThat(answer.wasKnownAt(Instant.parse("2026-10-14T01:00:00Z"))).isFalse();
    }

    private static String quantityFrom(List<Observation<Position>> result) {
        return result.isEmpty() ? "none" : result.get(0).value().quantity().value().toPlainString();
    }
}
