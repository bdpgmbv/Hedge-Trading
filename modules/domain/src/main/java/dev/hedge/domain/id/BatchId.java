package dev.hedge.domain.id;

public record BatchId(String value) {

    public BatchId {
        value = Identifiers.validated(value, "a Batch id");
    }

    public static BatchId of(String value) {
        return new BatchId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
