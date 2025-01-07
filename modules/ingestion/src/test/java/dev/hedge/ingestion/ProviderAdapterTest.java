package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.ingestion.provider.CustodianACsvSource;
import dev.hedge.ingestion.provider.CustodianBJsonSource;
import dev.hedge.ingestion.provider.VendorMarketDataSource;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.DeliveryStyle;
import dev.hedge.ingestion.source.RawBatch;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The three providers differ in format, identifier scheme, delivery style and how completeness can
 * be checked at all. Testing them together is deliberate: it is the differences that matter, and a
 * set of providers that are cosmetic variations of each other teaches nothing.
 */
class ProviderAdapterTest {

    private static final Instant RECEIVED = Instant.parse("2026-10-14T02:15:00Z");
    private static final Instant MONDAY_CLOSE = Instant.parse("2026-10-13T21:00:00Z");

    private final CustodianACsvSource custodianA = new CustodianACsvSource();
    private final CustodianBJsonSource custodianB = new CustodianBJsonSource();
    private final VendorMarketDataSource vendorA = new VendorMarketDataSource("VENDOR_A");

    // ---------- Custodian A, CSV snapshot, CUSIP, trailer ----------

    @Test
    @DisplayName("custodian A: a complete snapshot parses and verifies against its trailer")
    void custodian_a_reads_a_complete_snapshot() {
        RawBatch batch = custodianA.read(Artifact.of("a.csv", """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                POSITION,ACC-00001,594918104,2300.00000000
                TRAILER,2
                """), RECEIVED);

        assertThat(batch.completeness().isComplete()).isTrue();
        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.records().get(0).payload()).isEqualTo("ACC-00001,037833100,1500.00000000");
        assertThat(batch.records().get(0).effectiveAt()).isEqualTo(MONDAY_CLOSE);
        assertThat(batch.source().deliveryStyle()).isEqualTo(DeliveryStyle.SNAPSHOT);
    }

    @Test
    @DisplayName("custodian A: a file cut short by a failed transfer is caught by the trailer")
    void custodian_a_detects_truncation() {
        RawBatch batch = custodianA.read(Artifact.of("cut.csv", """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                TRAILER,4
                """), RECEIVED);

        assertThat(batch.completeness().isComplete()).isFalse();
        assertThat(batch.completeness().problem().orElseThrow()).contains("declared 4");
    }

    @Test
    @DisplayName("custodian A: a file with no trailer cannot be verified and says so")
    void custodian_a_requires_a_trailer() {
        RawBatch batch = custodianA.read(Artifact.of("notrailer.csv", """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                """), RECEIVED);

        assertThat(batch.completeness().problem().orElseThrow()).contains("no TRAILER");
    }

    @Test
    @DisplayName("custodian A: a price row is quarantined, because custodians do not price")
    void custodian_a_may_not_supply_prices() {
        RawBatch batch = custodianA.read(Artifact.of("overreach.csv", """
                HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT
                POSITION,ACC-00001,037833100,1500.00000000
                PRICE,037833100,USD,184.52
                TRAILER,1
                """), RECEIVED);

        assertThat(batch.completeness().quarantined()).isTrue();
        assertThat(batch.completeness().problem().orElseThrow()).contains("may only report");
    }

    // ---------- Custodian B, JSON incremental, ISIN, no trailer ----------

    @Test
    @DisplayName("custodian B: an incremental change set parses with its ISINs")
    void custodian_b_reads_an_incremental_batch() {
        RawBatch batch = custodianB.read(Artifact.of("b.json", """
                {"provider":"CUSTODIAN_B","effectiveAt":"2026-10-13T21:00:00Z",
                 "delivery":"INCREMENTAL","batchSequence":42,
                 "changes":[{"recordId":"B-1001","version":1,"account":"CUST-B-99",
                             "isin":"US0378331005","quantity":"100"}]}
                """), RECEIVED);

        assertThat(batch.size()).isEqualTo(1);
        assertThat(batch.records().get(0).payload()).isEqualTo("CUST-B-99,US0378331005,100");
        assertThat(batch.records().get(0).providerRecordId()).contains("B-1001");
        assertThat(batch.source().deliveryStyle()).isEqualTo(DeliveryStyle.INCREMENTAL);
    }

    @Test
    @DisplayName("custodian B: truncation shows up as invalid JSON, so the format is its own check")
    void custodian_b_detects_truncation_through_the_parser() {
        RawBatch batch = custodianB.read(Artifact.of("cut.json",
                "{\"effectiveAt\":\"2026-10-13T21:00:00Z\",\"changes\":[{\"account\":\"X\""), RECEIVED);

        assertThat(batch.completeness().isComplete()).isFalse();
        assertThat(batch.completeness().problem().orElseThrow()).contains("truncated");
        assertThat(batch.size()).isZero();
    }

    @Test
    @DisplayName("custodian B: market data in a custodian document is quarantined")
    void custodian_b_may_not_supply_market_data() {
        RawBatch batch = custodianB.read(Artifact.of("overreach.json", """
                {"effectiveAt":"2026-10-13T21:00:00Z","changes":[],
                 "prices":[{"isin":"US0378331005","price":"184.52"}]}
                """), RECEIVED);

        assertThat(batch.completeness().quarantined()).isTrue();
    }

    // ---------- Vendor, prices and fx rates, never positions ----------

    @Test
    @DisplayName("vendor: prices and fx rates arrive together and verify against the count")
    void vendor_reads_prices_and_rates() {
        RawBatch batch = vendorA.read(Artifact.of("v.txt", """
                # VENDOR_A 2026-10-13T21:00:00Z
                PRICE|037833100|USD|184.52
                FX|EUR|USD|1.1023
                COUNT|2
                """), RECEIVED);

        assertThat(batch.completeness().isComplete()).isTrue();
        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.records().get(1).payload()).isEqualTo("FX|EUR|USD|1.1023");
    }

    @Test
    @DisplayName("vendor: a position row is quarantined, the mirror of the custodian rule")
    void vendor_may_not_supply_positions() {
        RawBatch batch = vendorA.read(Artifact.of("overreach.txt", """
                # VENDOR_A 2026-10-13T21:00:00Z
                PRICE|037833100|USD|184.52
                POSITION|ACC-1|037833100|100
                COUNT|2
                """), RECEIVED);

        assertThat(batch.completeness().quarantined()).isTrue();
        assertThat(batch.completeness().problem().orElseThrow()).contains("may only");
    }

    @Test
    @DisplayName("every provider names a different identifier scheme")
    void the_providers_are_genuinely_different() {
        assertThat(custodianA.descriptor().identifierKind())
                .isNotEqualTo(custodianB.descriptor().identifierKind());
        assertThat(custodianA.descriptor().provides()).doesNotContainAnyElementsOf(
                vendorA.descriptor().provides());
    }
}
