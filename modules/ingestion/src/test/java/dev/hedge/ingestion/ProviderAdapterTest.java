package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.ingestion.provider.CustodianACsvSource;
import dev.hedge.ingestion.provider.CustodianBJsonSource;
import dev.hedge.ingestion.provider.CustodianCChunkedSource;
import dev.hedge.ingestion.provider.CustodianDFixSource;
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
    private final CustodianCChunkedSource custodianC = new CustodianCChunkedSource();
    private final CustodianDFixSource custodianD = new CustodianDFixSource();
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


    // ---------- Custodian C, chunked, provider-specific ids ----------

    @Test
    @DisplayName("custodian C: a chunk parses and remembers its place in the day")
    void custodian_c_reads_a_chunk() {
        RawBatch batch = custodianC.read(Artifact.of("c-1.txt", """
                #CHUNK 1/3 CUSTODIAN_C 2026-10-13T21:00:00Z
                P|CPROV-7781|ACCT9900|412.5
                P|CPROV-7782|ACCT9900|88
                #ENDCHUNK 2
                """), RECEIVED);

        assertThat(batch.completeness().isComplete()).isTrue();
        assertThat(batch.size()).isEqualTo(2);
        assertThat(batch.batchSequence()).contains("1/3");
        assertThat(batch.records().get(0).payload()).isEqualTo("ACCT9900,CPROV-7781,412.5");
    }

    @Test
    @DisplayName("custodian C: a chunk with no terminator may have been cut short")
    void custodian_c_requires_a_terminator() {
        RawBatch batch = custodianC.read(Artifact.of("c-cut.txt", """
                #CHUNK 2/3 CUSTODIAN_C 2026-10-13T21:00:00Z
                P|CPROV-7781|ACCT9900|412.5
                """), RECEIVED);

        assertThat(batch.completeness().problem().orElseThrow()).contains("cut short");
        assertThat(batch.batchSequence()).contains("2/3");
    }

    // ---------- Custodian D, FIX execution reports ----------

    @Test
    @DisplayName("custodian D: a filled execution report becomes a position change")
    void custodian_d_reads_a_fill() {
        RawBatch batch = custodianD.read(Artifact.of("d.fix",
                "8=FIX.4.4|35=8|49=CUSTODIAN_D|34=1021|52=20261013-21:00:00|"
                        + "48=459200101|22=1|54=1|32=100|31=184.52|39=2|150=F|1=ACCT9900|17=EXEC-1|"),
                RECEIVED);

        assertThat(batch.size()).isEqualTo(1);
        assertThat(batch.records().get(0).payload()).isEqualTo("ACCT9900,459200101,1,100");
        assertThat(batch.records().get(0).providerRecordId()).contains("EXEC-1");
        assertThat(batch.records().get(0).providerVersion()).contains("1021");
        assertThat(batch.records().get(0).effectiveAt())
                .isEqualTo(Instant.parse("2026-10-13T21:00:00Z"));
    }

    @Test
    @DisplayName("custodian D: a sell is carried as a negative quantity")
    void custodian_d_signs_a_sell() {
        RawBatch batch = custodianD.read(Artifact.of("d-sell.fix",
                "8=FIX.4.4|35=8|34=1022|52=20261013-21:05:00|48=459200101|22=1|54=2|32=40|39=2|150=F|1=ACCT9900|"),
                RECEIVED);

        assertThat(batch.records().get(0).payload()).isEqualTo("ACCT9900,459200101,1,-40");
    }

    @Test
    @DisplayName("custodian D: heartbeats and unfilled orders carry no position and are skipped")
    void custodian_d_ignores_everything_that_is_not_a_fill() {
        RawBatch batch = custodianD.read(Artifact.of("d-noise.fix",
                "8=FIX.4.4|35=0|34=1|52=20261013-21:00:00|\n"
                        + "8=FIX.4.4|35=D|34=2|52=20261013-21:00:01|55=IBM|54=1|38=100|\n"
                        + "8=FIX.4.4|35=8|34=3|52=20261013-21:00:02|150=0|39=0|48=459200101|22=1|32=0|1=ACCT9900|"),
                RECEIVED);

        assertThat(batch.size()).isZero();
    }

    @Test
    @DisplayName("custodian D: completeness is unknown, because the session layer is not implemented")
    void custodian_d_cannot_verify_completeness() {
        RawBatch batch = custodianD.read(Artifact.of("d.fix",
                "8=FIX.4.4|35=8|34=1|52=20261013-21:00:00|48=X|22=1|54=1|32=1|39=2|150=F|1=A|"),
                RECEIVED);

        assertThat(batch.completeness().declaredCount()).isEmpty();
        assertThat(batch.completeness().isComplete()).isTrue();
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
