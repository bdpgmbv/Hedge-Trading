package dev.hedge.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.domain.temporal.Provenance;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReconciliationEngineTest {

    private static final Instant EFFECTIVE = Instant.parse("2026-10-13T21:00:00Z");
    private static final Instant RECEIVED = Instant.parse("2026-10-14T02:00:00Z");
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");
    private static final SecurityId APPLE = SecurityId.of("SEC-APPLE");
    private static final SecurityId MSFT = SecurityId.of("SEC-MSFT");

    private final ReconciliationEngine engine = new ReconciliationEngine();

    private static Observation<Position> held(
            SecurityId security, String quantity, String observationId) {
        return held(security, quantity, observationId, EFFECTIVE, RECEIVED);
    }

    private static Observation<Position> held(
            SecurityId security, String quantity, String observationId,
            Instant effectiveAt, Instant receivedAt) {
        return new Observation<>(
                new Position(ACCOUNT, security, Quantity.of(quantity)),
                effectiveAt,
                receivedAt,
                new Provenance(
                        ProviderId.of("P"), ObservationId.of(observationId), "position-delimited-v1"));
    }

    @Test
    @DisplayName("holdings both sides agree on are matched, and the match is recorded")
    void agreement_is_a_match() {
        var run = engine.reconcile(
                "OURS", List.of(held(APPLE, "100", "L1")),
                "CUSTODIAN", List.of(held(APPLE, "100", "R1")),
                Tolerance.exact());

        assertThat(run.count(BreakType.MATCH)).isEqualTo(1);
        assertThat(run.needingAttention()).isEmpty();
    }

    @Test
    @DisplayName("a holding only the custodian reports is ADDED")
    void a_holding_only_they_have_is_added() {
        var run = engine.reconcile(
                "OURS", List.of(),
                "CUSTODIAN", List.of(held(APPLE, "100", "R1")),
                Tolerance.exact());

        assertThat(run.count(BreakType.ADDED)).isEqualTo(1);
        assertThat(run.breaks().get(0).explanation()).contains("never mentioned it");
    }

    @Test
    @DisplayName("a holding only we report is MISSING")
    void a_holding_only_we_have_is_missing() {
        var run = engine.reconcile(
                "OURS", List.of(held(APPLE, "100", "L1")),
                "CUSTODIAN", List.of(),
                Tolerance.exact());

        assertThat(run.count(BreakType.MISSING)).isEqualTo(1);
    }

    @Test
    @DisplayName("a quantity difference beyond tolerance is CHANGED, and names both numbers")
    void a_real_difference_is_changed() {
        var run = engine.reconcile(
                "OURS", List.of(held(APPLE, "500", "L1")),
                "CUSTODIAN", List.of(held(APPLE, "450", "R1")),
                Tolerance.exact());

        assertThat(run.count(BreakType.CHANGED)).isEqualTo(1);
        var found = run.breaks().get(0);
        // BigDecimal.equals compares scale too, so -5E+1 is not equal to -50 even though it is
        // the same number. Comparing by value is almost always what a money assertion wants.
        assertThat(found.difference().orElseThrow()).isEqualByComparingTo("-50");
        assertThat(found.explanation()).contains("500").contains("450");
        assertThat(found.leftEvidence()).contains(ObservationId.of("L1"));
        assertThat(found.rightEvidence()).contains(ObservationId.of("R1"));
    }

    @Test
    @DisplayName("a rounding-sized difference inside tolerance is not a break")
    void a_tiny_difference_is_tolerated() {
        var run = engine.reconcile(
                "OURS", List.of(held(APPLE, "1000.00000000", "L1")),
                "CUSTODIAN", List.of(held(APPLE, "1000.00000001", "R1")),
                Tolerance.of("0.0001", "0"));

        assertThat(run.count(BreakType.MATCH)).isEqualTo(1);
        assertThat(run.count(BreakType.CHANGED)).isZero();
    }

    @Test
    @DisplayName("the same difference is a break under an exact tolerance")
    void tolerance_is_what_decides() {
        var run = engine.reconcile(
                "OURS", List.of(held(APPLE, "1000.00000000", "L1")),
                "CUSTODIAN", List.of(held(APPLE, "1000.00000001", "R1")),
                Tolerance.exact());

        assertThat(run.count(BreakType.CHANGED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a mixed portfolio produces one classified break per holding and no more")
    void every_holding_is_classified_exactly_once() {
        var run = engine.reconcile(
                "OURS", List.of(held(APPLE, "100", "L1"), held(MSFT, "200", "L2")),
                "CUSTODIAN", List.of(held(APPLE, "150", "R1")),
                Tolerance.exact());

        assertThat(run.breaks()).hasSize(2);
        assertThat(run.count(BreakType.CHANGED)).isEqualTo(1);
        assertThat(run.count(BreakType.MISSING)).isEqualTo(1);
        assertThat(run.comparisons()).isEqualTo(2);
    }

    @Test
    @DisplayName("comparisons grow with the size of the inputs, not with their product")
    void the_engine_is_indexed_not_nested() {
        int size = 20_000;
        List<Observation<Position>> left = new ArrayList<>(size);
        List<Observation<Position>> right = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            SecurityId security = SecurityId.of("SEC-" + index);
            left.add(new Observation<>(
                    new Position(ACCOUNT, security, Quantity.of("100")), EFFECTIVE, RECEIVED,
                    new Provenance(ProviderId.of("P"), ObservationId.of("L" + index), "v1")));
            right.add(new Observation<>(
                    new Position(ACCOUNT, security, Quantity.of("100")), EFFECTIVE, RECEIVED,
                    new Provenance(ProviderId.of("P"), ObservationId.of("R" + index), "v1")));
        }

        var run = engine.reconcile("OURS", left, "CUSTODIAN", right, Tolerance.exact());

        // A nested comparison would have performed 400,000,000 of these.
        assertThat(run.comparisons()).isEqualTo(size);
        assertThat(run.count(BreakType.MATCH)).isEqualTo(size);
    }

    // ---------- corrections and duplicates ----------

    @Test
    @DisplayName("the same value restated is a DUPLICATE and must change nothing")
    void a_restated_value_is_a_duplicate() {
        var findings = new CorrectionDetector().classify(List.of(
                held(APPLE, "100", "O1", EFFECTIVE, RECEIVED),
                held(APPLE, "100", "O2", EFFECTIVE, RECEIVED.plusSeconds(3600))));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(BreakType.DUPLICATE);
    }

    @Test
    @DisplayName("a different value for the same business moment is a CORRECTION")
    void a_changed_value_for_the_same_day_is_a_correction() {
        var findings = new CorrectionDetector().classify(List.of(
                held(APPLE, "1500", "O1", EFFECTIVE, RECEIVED),
                held(APPLE, "900", "O2", EFFECTIVE, RECEIVED.plusSeconds(86_400))));

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).type()).isEqualTo(BreakType.CORRECTION);
        assertThat(findings.get(0).difference().orElseThrow()).isEqualByComparingTo("-600");
    }

    @Test
    @DisplayName("tomorrow's position is neither a duplicate nor a correction")
    void a_later_business_day_is_not_a_restatement() {
        var findings = new CorrectionDetector().classify(List.of(
                held(APPLE, "1500", "O1", EFFECTIVE, RECEIVED),
                held(APPLE, "1600", "O2", EFFECTIVE.plusSeconds(86_400), RECEIVED.plusSeconds(86_400))));

        assertThat(findings).isEmpty();
    }

    // ---------- conflicts ----------

    @Test
    @DisplayName("two equally ranked sources disagreeing is a CONFLICT, not a casting vote")
    void equal_sources_that_disagree_produce_a_conflict() {
        var conflict = new ConflictDetector().detect(
                ACCOUNT, APPLE,
                Map.of("VENDOR_A", new BigDecimal("184.52"), "VENDOR_B", new BigDecimal("191.10")),
                Tolerance.of("0.01", "0.001"));

        assertThat(conflict).isPresent();
        assertThat(conflict.orElseThrow().type()).isEqualTo(BreakType.CONFLICT);
        assertThat(conflict.orElseThrow().explanation()).contains("VENDOR_A").contains("VENDOR_B");
    }

    @Test
    @DisplayName("equally ranked sources that agree closely enough are not a conflict")
    void equal_sources_that_agree_are_fine() {
        var conflict = new ConflictDetector().detect(
                ACCOUNT, APPLE,
                Map.of("VENDOR_A", new BigDecimal("184.52"), "VENDOR_B", new BigDecimal("184.53")),
                Tolerance.of("0.01", "0.001"));

        assertThat(conflict).isEmpty();
    }

    @Test
    @DisplayName("one source alone cannot conflict with anything")
    void a_single_source_is_never_a_conflict() {
        var conflict = new ConflictDetector().detect(
                ACCOUNT, APPLE, Map.of("VENDOR_A", new BigDecimal("184.52")), Tolerance.exact());

        assertThat(conflict).isEmpty();
    }
}
