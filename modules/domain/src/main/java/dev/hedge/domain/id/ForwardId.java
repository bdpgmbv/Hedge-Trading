package dev.hedge.domain.id;
public record ForwardId(String value) {

    public ForwardId {
        value = Identifiers.validated(value, "a forward id");
    }

    public static ForwardId of(String value) {
        return new ForwardId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}