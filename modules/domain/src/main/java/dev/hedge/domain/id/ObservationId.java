package dev.hedge.domain.id;

public record ObservationId(String value) {

    public ObservationId {
        value = Identifiers.validated(value, "a Observation id");
    }

    public static ObservationId of(String value) {
        return new ObservationId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
