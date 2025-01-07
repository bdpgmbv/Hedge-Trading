package dev.hedge.domain.id;

public record ProviderId(String value) {

    public ProviderId {
        value = Identifiers.validated(value, "a Provider id");
    }

    public static ProviderId of(String value) {
        return new ProviderId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
