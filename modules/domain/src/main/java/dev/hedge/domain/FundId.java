package dev.hedge.domain;

public record FundId(String value) {

    public FundId {
        value = Identifiers.validated(value, "a fund id");
    }

    public static FundId of(String value) {
        return new FundId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}