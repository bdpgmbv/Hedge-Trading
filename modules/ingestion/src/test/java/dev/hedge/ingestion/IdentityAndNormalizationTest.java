package dev.hedge.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.BatchId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.temporal.ObservationKind;
import dev.hedge.ingestion.identity.AccountMappingRegistry;
import dev.hedge.ingestion.identity.Resolution;
import dev.hedge.ingestion.identity.SecurityIdentifierRegistry;
import dev.hedge.ingestion.normalize.DelimitedPositionNormalizer;
import dev.hedge.ingestion.normalize.FixPositionNormalizer;
import dev.hedge.ingestion.normalize.MarketDataNormalizer;
import dev.hedge.ingestion.normalize.NormalizationResult;
import dev.hedge.ingestion.normalize.NormalizedPosition;
import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.DeliveryStyle;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.SourceDescriptor;
import dev.hedge.persistence.DataSources;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentityAndNormalizationTest {

    private static final Instant WHEN = Instant.parse("2026-10-13T21:00:00Z");

    /** Apple, as three custodians and one vendor each choose to name it. */
    private static final SecurityId APPLE = SecurityId.of("SEC-APPLE");
    private static final String APPLE_CUSIP = "037833100";
    private static final String APPLE_ISIN = "US0378331005";
    private static final String APPLE_PROVIDER_CODE = "CPROV-7781";

    private static SourceDescriptor custodian(String id, IdentifierKind kind) {
        return new SourceDescriptor(
                ProviderId.of(id), Set.of(ObservationKind.POSITION), kind, DeliveryStyle.SNAPSHOT, "test");
    }

    private static final SourceDescriptor A = custodian("CUSTODIAN_A", IdentifierKind.CUSIP);
    private static final SourceDescriptor B = custodian("CUSTODIAN_B", IdentifierKind.ISIN);
    private static final SourceDescriptor C = custodian("CUSTODIAN_C", IdentifierKind.PROVIDER_SPECIFIC);

    private static DataSource dataSource;
    private SecurityIdentifierRegistry securities;
    private AccountMappingRegistry accounts;

    private final DelimitedPositionNormalizer delimited = new DelimitedPositionNormalizer();
    private final FixPositionNormalizer fix = new FixPositionNormalizer();
    private final MarketDataNormalizer marketData = new MarketDataNormalizer();

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
        securities = new SecurityIdentifierRegistry(dataSource);
        accounts = new AccountMappingRegistry(dataSource);
    }

    private static StoredObservation observation(String payload) {
        return new StoredObservation(
                ObservationId.of("OBS-1"), BatchId.of("BATCH-1"), 0,
                Optional.empty(), Optional.empty(), WHEN, WHEN, payload);
    }

    // ---------- Layer 39, identity ----------

    @Test
    @DisplayName("three custodians naming a security three ways resolve to one identity")
    void three_names_one_security() {
        securities.map(IdentifierKind.CUSIP, APPLE_CUSIP, APPLE, "reference data");
        securities.map(IdentifierKind.ISIN, APPLE_ISIN, APPLE, "reference data");
        securities.map(IdentifierKind.PROVIDER_SPECIFIC, APPLE_PROVIDER_CODE, APPLE, "custodian C mapping");

        assertThat(securities.resolve(IdentifierKind.CUSIP, APPLE_CUSIP))
                .isEqualTo(new Resolution.Resolved<>(APPLE));
        assertThat(securities.resolve(IdentifierKind.ISIN, APPLE_ISIN))
                .isEqualTo(new Resolution.Resolved<>(APPLE));
        assertThat(securities.resolve(IdentifierKind.PROVIDER_SPECIFIC, APPLE_PROVIDER_CODE))
                .isEqualTo(new Resolution.Resolved<>(APPLE));
        assertThat(securities.namesOf(APPLE)).hasSize(3);
    }

    @Test
    @DisplayName("an unknown identifier is reported, never guessed at")
    void an_unknown_identifier_is_unmapped() {
        Resolution<SecurityId> resolution = securities.resolve(IdentifierKind.CUSIP, "999999999");

        assertThat(resolution).isInstanceOf(Resolution.Unmapped.class);
        assertThat(((Resolution.Unmapped<SecurityId>) resolution).reason()).contains("999999999");
    }

    @Test
    @DisplayName("the same account code at two custodians is two different accounts")
    void account_codes_are_only_unique_within_a_provider() {
        accounts.map(ProviderId.of("CUSTODIAN_A"), "ACC-00001", AccountId.of("ACC-REAL-1"));
        accounts.map(ProviderId.of("CUSTODIAN_C"), "ACC-00001", AccountId.of("ACC-REAL-2"));

        assertThat(accounts.resolve(ProviderId.of("CUSTODIAN_A"), "ACC-00001"))
                .isEqualTo(new Resolution.Resolved<>(AccountId.of("ACC-REAL-1")));
        assertThat(accounts.resolve(ProviderId.of("CUSTODIAN_C"), "ACC-00001"))
                .isEqualTo(new Resolution.Resolved<>(AccountId.of("ACC-REAL-2")));
    }

    // ---------- Layer 38, normalization ----------

    @Test
    @DisplayName("the identifier scheme comes from the provider, because the text cannot say")
    void the_scheme_comes_from_the_descriptor() {
        var fromA = delimited.normalize(observation("ACC-00001,037833100,1500.00"), A);
        var fromB = delimited.normalize(observation("CUST-B-99,US0378331005,100"), B);

        assertThat(accepted(fromA).identifierKind()).isEqualTo(IdentifierKind.CUSIP);
        assertThat(accepted(fromB).identifierKind()).isEqualTo(IdentifierKind.ISIN);
        assertThat(accepted(fromA).quantity()).isEqualTo(new BigDecimal("1500.00"));
    }

    @Test
    @DisplayName("FIX carries its scheme per record, so tag 22 decides and not the provider")
    void fix_decides_the_scheme_per_record() {
        var asCusip = fix.normalize(observation("ACCT9900,037833100,1,100"), C);
        var asIsin = fix.normalize(observation("ACCT9900,US0378331005,4,-40"), C);

        assertThat(accepted(asCusip).identifierKind()).isEqualTo(IdentifierKind.CUSIP);
        assertThat(accepted(asIsin).identifierKind()).isEqualTo(IdentifierKind.ISIN);
        assertThat(accepted(asIsin).quantity()).isEqualTo(new BigDecimal("-40"));
    }

    @Test
    @DisplayName("an unrecognised FIX identifier scheme is rejected rather than assumed")
    void an_unknown_fix_scheme_is_rejected() {
        var result = fix.normalize(observation("ACCT9900,XYZ,99,100"), C);

        assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
        assertThat(((NormalizationResult.Rejected<NormalizedPosition>) result).reason()).contains("tag 22");
    }

    @Test
    @DisplayName("a quantity that is not an exact decimal is rejected, not rounded")
    void a_bad_quantity_is_rejected() {
        var result = delimited.normalize(observation("ACC-1,037833100,about a thousand"), A);

        assertThat(result).isInstanceOf(NormalizationResult.Rejected.class);
    }

    @Test
    @DisplayName("every accepted value carries the version of the rules that produced it")
    void the_rules_version_is_stamped_on_the_result() {
        var result = delimited.normalize(observation("ACC-1,037833100,10"), A);

        assertThat(((NormalizationResult.Accepted<NormalizedPosition>) result).normalizationVersion())
                .isEqualTo("position-delimited-v1");
        assertThat(fix.version()).isNotEqualTo(delimited.version());
    }

    @Test
    @DisplayName("a vendor row is either a price or a rate, and says which")
    void market_data_separates_prices_from_rates() {
        var price = marketData.normalize(observation("PRICE|037833100|USD|184.52"), A);
        var rate = marketData.normalize(observation("FX|EUR|USD|1.1023"), A);

        var asPrice = (NormalizationResult.Accepted<MarketDataNormalizer.MarketDatum>) price;
        var asRate = (NormalizationResult.Accepted<MarketDataNormalizer.MarketDatum>) rate;
        assertThat(asPrice.value().price()).isPresent();
        assertThat(asPrice.value().rate()).isEmpty();
        assertThat(asRate.value().rate().orElseThrow().rate()).isEqualTo(new BigDecimal("1.1023"));
    }

    @Test
    @DisplayName("a negative price and a zero fx rate are both refused")
    void impossible_market_data_is_refused() {
        assertThat(marketData.normalize(observation("PRICE|037833100|USD|-1.00"), A))
                .isInstanceOf(NormalizationResult.Rejected.class);
        assertThat(marketData.normalize(observation("FX|EUR|USD|0"), A))
                .isInstanceOf(NormalizationResult.Rejected.class);
        assertThat(marketData.normalize(observation("FX|EUR|EUR|1"), A))
                .isInstanceOf(NormalizationResult.Rejected.class);
    }

    private static NormalizedPosition accepted(NormalizationResult<NormalizedPosition> result) {
        return ((NormalizationResult.Accepted<NormalizedPosition>) result).value();
    }
}
