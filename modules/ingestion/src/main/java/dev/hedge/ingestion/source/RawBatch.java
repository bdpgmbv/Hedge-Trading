package dev.hedge.ingestion.source;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Everything one delivered artifact produced, before any interpretation. */
public record RawBatch(
        SourceDescriptor source,
        Artifact artifact,
        Instant receivedAt,
        List<RawRecord> records,
        BatchCompleteness completeness) {

    public RawBatch {
        Objects.requireNonNull(source, "a batch needs its source");
        Objects.requireNonNull(artifact, "a batch needs its artifact");
        Objects.requireNonNull(receivedAt, "a batch needs a received time");
        Objects.requireNonNull(completeness, "a batch needs a completeness result");
        records = List.copyOf(Objects.requireNonNull(records, "records must not be null"));
    }

    public int size() {
        return records.size();
    }
}
