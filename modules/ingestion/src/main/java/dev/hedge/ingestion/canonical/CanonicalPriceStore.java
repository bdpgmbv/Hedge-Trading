package dev.hedge.ingestion.canonical;

import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Price;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.domain.temporal.Provenance;
import dev.hedge.ingestion.source.IngestionException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/** Prices as each vendor stated them, bitemporal and append-only, exactly like positions. */
public final class CanonicalPriceStore {

    private final DataSource dataSource;

    public CanonicalPriceStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "a data source is required");
    }

    public void append(ProviderId provider, Observation<Price> observation) {
        Price price = observation.value();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "insert into canonical_prices (observation_id, provider_id, security_id,"
                                + " amount, currency, effective_at, received_at, normalization_version)"
                                + " values (?, ?, ?, ?, ?, ?, ?, ?) on conflict (observation_id) do nothing")) {
            statement.setString(1, observation.from().observation().value());
            statement.setString(2, provider.value());
            statement.setString(3, price.securityId().value());
            statement.setBigDecimal(4, price.value().amount());
            statement.setString(5, price.value().currency().getCurrencyCode());
            statement.setTimestamp(6, Timestamp.from(observation.effectiveAt()));
            statement.setTimestamp(7, Timestamp.from(observation.receivedAt()));
            statement.setString(8, observation.from().normalizationVersion());
            statement.executeUpdate();
            connection.commit();
        } catch (SQLException failure) {
            throw new IngestionException("could not append a canonical price", failure);
        }
    }

    /**
     * The best price for a security at a business moment as known at a knowledge moment, choosing
     * between vendors by the supplied ranking.
     *
     * <p>Two vendors disagreeing about a bond is normal rather than an error: most prices in a
     * real portfolio were never traded at, they were evaluated, and two models legitimately
     * differ. So a ranking decides, and the answer records which rung it came from.
     */
    public Optional<SelectedPrice> select(
            SecurityId security, Instant effectiveAt, Instant knownAt, List<ProviderId> ranking) {
        Objects.requireNonNull(security, "a security is required");
        Objects.requireNonNull(ranking, "a provider ranking is required");

        for (int rank = 0; rank < ranking.size(); rank++) {
            ProviderId provider = ranking.get(rank);
            Optional<Observation<Price>> found = latestFrom(provider, security, effectiveAt, knownAt);
            if (found.isPresent()) {
                return Optional.of(new SelectedPrice(
                        found.orElseThrow(),
                        provider,
                        rank + 1,
                        rank == 0
                                ? "the primary source priced it"
                                : "rank " + (rank + 1) + " used because higher ranked sources had no price"));
            }
        }
        return Optional.empty();
    }

    /** A chosen price, the source it came from, and why that source won. */
    public record SelectedPrice(
            Observation<Price> price, ProviderId provider, int rank, String reason) {}

    private Optional<Observation<Price>> latestFrom(
            ProviderId provider, SecurityId security, Instant effectiveAt, Instant knownAt) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "select observation_id, amount, currency, effective_at, received_at,"
                                + " normalization_version from canonical_prices"
                                + " where provider_id = ? and security_id = ?"
                                + " and effective_at <= ? and received_at <= ?"
                                + " order by effective_at desc, received_at desc limit 1")) {
            statement.setString(1, provider.value());
            statement.setString(2, security.value());
            statement.setTimestamp(3, Timestamp.from(effectiveAt));
            statement.setTimestamp(4, Timestamp.from(knownAt));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    return Optional.empty();
                }
                return Optional.of(new Observation<>(
                        new Price(security, new Money(
                                rows.getBigDecimal(2), Currency.getInstance(rows.getString(3).trim()))),
                        rows.getTimestamp(4).toInstant(),
                        rows.getTimestamp(5).toInstant(),
                        new Provenance(provider, ObservationId.of(rows.getString(1)), rows.getString(6))));
            }
        } catch (SQLException failure) {
            throw new IngestionException("could not read a price for " + security, failure);
        }
    }

    /** Every vendor's current opinion, which is what a price conflict is measured across. */
    public List<SelectedPrice> allOpinions(
            SecurityId security, Instant effectiveAt, Instant knownAt, List<ProviderId> providers) {
        List<SelectedPrice> opinions = new ArrayList<>();
        for (int rank = 0; rank < providers.size(); rank++) {
            ProviderId provider = providers.get(rank);
            int finalRank = rank + 1;
            latestFrom(provider, security, effectiveAt, knownAt)
                    .ifPresent(price -> opinions.add(
                            new SelectedPrice(price, provider, finalRank, "vendor opinion")));
        }
        return opinions;
    }
}
