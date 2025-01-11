package dev.hedge.ingestion;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Position;
import dev.hedge.domain.temporal.Observation;
import dev.hedge.domain.temporal.Provenance;
import dev.hedge.ingestion.identity.CachingSecurityIdentifierRegistry;
import dev.hedge.ingestion.identity.SecurityIdentifierRegistry;
import dev.hedge.ingestion.normalize.DelimitedPositionNormalizer;
import dev.hedge.ingestion.provider.CustodianACsvSource;
import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.RawBatch;
import dev.hedge.persistence.DataSources;
import dev.hedge.reconciliation.ReconciliationEngine;
import dev.hedge.reconciliation.Tolerance;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Layer 45. Measures each stage separately, because the stage that dominates is rarely the one
 * people assume. "Parsing is the bottleneck" is a guess until the numbers say so.
 */
class PerformanceProbe {

    private static final Instant WHEN = Instant.parse("2026-10-13T21:00:00Z");
    private static final int VALIDATION_RECORDS = 1_000_000;
    private static final int RECONCILIATION_RECORDS = 100_000;
    private static final int IDENTITY_LOOKUPS = 20_000;

    @Test
    void run() {
        measureValidation();
        measureNormalization();
        measureReconciliation();
        measureIdentityLookups();
    }

    /** Target: one million records validated in under sixty seconds. */
    private static void measureValidation() {
        StringBuilder file = new StringBuilder("HEADER,CUSTODIAN_A,2026-10-13,SNAPSHOT\n");
        for (int index = 0; index < VALIDATION_RECORDS; index++) {
            file.append("POSITION,ACC-").append(index % 1000)
                    .append(",SEC-").append(index % 20_000)
                    .append(",").append(100 + (index % 900)).append(".00000000\n");
        }
        file.append("TRAILER,").append(VALIDATION_RECORDS).append('\n');
        Artifact artifact = Artifact.of("big.csv", file.toString());

        long start = System.nanoTime();
        RawBatch batch = new CustodianACsvSource().read(artifact, WHEN);
        long millis = (System.nanoTime() - start) / 1_000_000;

        report("validation", VALIDATION_RECORDS, millis, "target < 60000 ms",
                "complete=" + batch.completeness().isComplete() + " bytes=" + artifact.sizeInBytes());
    }

    private static void measureNormalization() {
        var normalizer = new DelimitedPositionNormalizer();
        var descriptor = new CustodianACsvSource().descriptor();
        List<StoredObservation> observations = new ArrayList<>(VALIDATION_RECORDS / 10);
        for (int index = 0; index < VALIDATION_RECORDS / 10; index++) {
            observations.add(new StoredObservation(
                    ObservationId.of("O-" + index), dev.hedge.domain.id.BatchId.of("B"), index,
                    Optional.empty(), Optional.empty(), WHEN, WHEN,
                    "ACC-" + (index % 1000) + ",037833100," + (100 + index % 900) + ".00000000"));
        }

        long start = System.nanoTime();
        int accepted = 0;
        for (StoredObservation observation : observations) {
            if (normalizer.normalize(observation, descriptor).isAccepted()) {
                accepted++;
            }
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        report("normalization", observations.size(), millis, "no stated target",
                "accepted=" + accepted);
    }

    /** Target: more than thirty thousand reconciliation records per second. */
    private static void measureReconciliation() {
        List<Observation<Position>> left = new ArrayList<>(RECONCILIATION_RECORDS);
        List<Observation<Position>> right = new ArrayList<>(RECONCILIATION_RECORDS);
        for (int index = 0; index < RECONCILIATION_RECORDS; index++) {
            SecurityId security = SecurityId.of("SEC-" + index);
            AccountId account = AccountId.of("ACC-" + (index % 500));
            left.add(position(account, security, "100"));
            // one in a thousand genuinely differs, which is a realistic break rate
            right.add(position(account, security, index % 1000 == 0 ? "101" : "100"));
        }

        long start = System.nanoTime();
        var run = new ReconciliationEngine().reconcile("A", left, "B", right, Tolerance.exact());
        long millis = (System.nanoTime() - start) / 1_000_000;

        long perSecond = millis == 0 ? RECONCILIATION_RECORDS : RECONCILIATION_RECORDS * 1000L / millis;
        report("reconciliation", RECONCILIATION_RECORDS, millis, "target > 30000 rec/sec",
                "breaks=" + run.needingAttention().size() + " perSecond=" + perSecond);
    }

    private static void measureIdentityLookups() {
        DataSource dataSource = IngestionTestDatabase.start();
        try {
            clearIdentity(dataSource);
            var registry = new SecurityIdentifierRegistry(dataSource);
            for (int index = 0; index < 1_000; index++) {
                registry.map(IdentifierKind.CUSIP, "CUSIP-" + index,
                        SecurityId.of("SEC-" + index), "probe");
            }

            long start = System.nanoTime();
            int resolved = 0;
            for (int index = 0; index < IDENTITY_LOOKUPS; index++) {
                if (registry.resolve(IdentifierKind.CUSIP, "CUSIP-" + (index % 1_000)).isResolved()) {
                    resolved++;
                }
            }
            long millis = (System.nanoTime() - start) / 1_000_000;
            report("identity lookup", IDENTITY_LOOKUPS, millis, "one round trip per record",
                    "resolved=" + resolved);

            var cached = new CachingSecurityIdentifierRegistry(registry);
            start = System.nanoTime();
            int fromCache = 0;
            for (int index = 0; index < IDENTITY_LOOKUPS; index++) {
                if (cached.resolve(IdentifierKind.CUSIP, "CUSIP-" + (index % 1_000)).isResolved()) {
                    fromCache++;
                }
            }
            long cachedMillis = (System.nanoTime() - start) / 1_000_000;
            report("identity cached", IDENTITY_LOOKUPS, cachedMillis, "1000 round trips total",
                    "resolved=" + fromCache + " cachedEntries=" + cached.cachedEntries()
                            + " speedup=" + (cachedMillis == 0 ? ">1000" : (millis / Math.max(cachedMillis, 1)))
                            + "x");
        } finally {
            DataSources.close(dataSource);
        }
    }

    private static Observation<Position> position(AccountId account, SecurityId security, String qty) {
        return new Observation<>(
                new Position(account, security, Quantity.of(qty)), WHEN, WHEN,
                new Provenance(ProviderId.of("P"), ObservationId.of("O-" + security.value()), "v1"));
    }

    private static void clearIdentity(DataSource dataSource) {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("truncate table security_identifiers cascade");
            connection.commit();
        } catch (SQLException failure) {
            throw new IllegalStateException("could not clear identity tables", failure);
        }
    }

    private static void report(String stage, int records, long millis, String target, String detail) {
        long perSecond = millis == 0 ? records : records * 1000L / millis;
        System.out.printf("PROBE %-16s %,9d records  %6d ms  %,9d/sec   %-24s %s%n",
                stage, records, millis, perSecond, target, detail);
    }
}
