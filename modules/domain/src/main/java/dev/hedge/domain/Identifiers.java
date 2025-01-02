package dev.hedge.domain;

final class Identifiers {

    private Identifiers() {}

    static String validated(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value.trim();
    }
}