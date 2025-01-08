package dev.hedge.ingestion.provider;

import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.temporal.ObservationKind;
import dev.hedge.ingestion.source.Artifact;
import dev.hedge.ingestion.source.BatchCompleteness;
import dev.hedge.ingestion.source.DeliveryStyle;
import dev.hedge.ingestion.source.IdentifierKind;
import dev.hedge.ingestion.source.ObservationSource;
import dev.hedge.ingestion.source.RawBatch;
import dev.hedge.ingestion.source.RawRecord;
import dev.hedge.ingestion.source.SourceDescriptor;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Custodian D: filled trades as FIX execution reports.
 *
 * <pre>
 * 8=FIX.4.4|35=8|49=CUSTODIAN_D|34=1021|52=20261013-21:00:00|
 * 48=459200101|22=1|54=1|32=100|31=184.52|39=2|150=F|1=ACCT9900|
 * </pre>
 *
 * <p><strong>This is a message parser, not a FIX engine.</strong> There is no session layer here:
 * no logon, no heartbeats, no sequence-gap recovery, no resend requests. Those matter when you are
 * connected to a broker, and the problems they solve — ordered delivery, detecting a gap,
 * processing a message exactly once — are the same problems Kafka solves in Phase 5, with better
 * tooling. Building both would be learning one thing twice.
 *
 * <p>It also parses flat tag/value pairs only. Repeating groups such as NoPartyIDs are not
 * handled, because execution reports for position keeping do not require them.
 *
 * <p>Tag 22 says which scheme tag 48 uses, so the same provider can name a security by CUSIP on
 * one message and ISIN on the next — an identifier scheme that varies per record, which none of
 * the other three custodians do.
 */
public final class CustodianDFixSource implements ObservationSource {

    private static final SourceDescriptor DESCRIPTOR = new SourceDescriptor(
            ProviderId.of("CUSTODIAN_D"),
            Set.of(ObservationKind.POSITION),
            IdentifierKind.PROVIDER_SPECIFIC,
            DeliveryStyle.INCREMENTAL,
            "fix");

    private static final DateTimeFormatter SENDING_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd-HH:mm:ss");

    private static final String EXECUTION_REPORT = "8";
    private static final String TRADE = "F";

    @Override
    public SourceDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public RawBatch read(Artifact artifact, Instant receivedAt) {
        Objects.requireNonNull(artifact, "an artifact is required");
        Objects.requireNonNull(receivedAt, "a received time is required");

        List<RawRecord> records = new ArrayList<>();
        String highestSequence = null;

        for (String message : artifact.text().split("\n", -1)) {
            String trimmed = message.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            Map<String, String> tags = parseTags(trimmed);

            if (!EXECUTION_REPORT.equals(tags.get("35"))) {
                continue; // heartbeats, logons and anything else carry no position information
            }
            if (!TRADE.equals(tags.get("150"))) {
                continue; // only an actual fill changes what is held
            }

            String sendingTime = tags.get("52");
            if (sendingTime == null) {
                return malformed(artifact, receivedAt, records,
                        "an execution report carries no SendingTime (52), so it cannot be dated");
            }
            Instant effectiveAt;
            try {
                effectiveAt = LocalDateTime.parse(sendingTime, SENDING_TIME).toInstant(ZoneOffset.UTC);
            } catch (java.time.format.DateTimeParseException failure) {
                return malformed(artifact, receivedAt, records,
                        "SendingTime '" + sendingTime + "' is not in the FIX UTCTimestamp format");
            }

            String signedQuantity = "2".equals(tags.get("54"))
                    ? "-" + tags.getOrDefault("32", "0")
                    : tags.getOrDefault("32", "0");

            records.add(new RawRecord(
                    records.size(),
                    String.join(",",
                            tags.getOrDefault("1", ""),
                            tags.getOrDefault("48", ""),
                            tags.getOrDefault("22", ""),
                            signedQuantity),
                    Optional.ofNullable(tags.get("17")),
                    Optional.ofNullable(tags.get("34")),
                    effectiveAt));
            highestSequence = tags.get("34");
        }

        // A FIX file carries no trailer count; the session layer we are not implementing is what
        // would normally guarantee nothing was lost, so completeness here is simply unknown.
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.unverifiable(records.size()), Optional.ofNullable(highestSequence));
    }

    private static Map<String, String> parseTags(String message) {
        Map<String, String> tags = new HashMap<>();
        for (String pair : message.split("[|\u0001]")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                tags.put(pair.substring(0, equals), pair.substring(equals + 1));
            }
        }
        return tags;
    }

    private static RawBatch malformed(
            Artifact artifact, Instant receivedAt, List<RawRecord> records, String problem) {
        return new RawBatch(DESCRIPTOR, artifact, receivedAt, records,
                BatchCompleteness.malformed(records.size(), problem));
    }
}
