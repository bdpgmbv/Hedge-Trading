package dev.hedge.domain;

public record SecurityId(String value) {

    public SecurityId {
        value = Identifiers.validated(value, "a security id");
    }

    public static SecurityId of(String value) {
        return new SecurityId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}