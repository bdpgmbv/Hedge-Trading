package dev.hedge.domain.id;
public record AccountId(String value) {

    public AccountId {
        value = Identifiers.validated(value, "an account id");
    }

    public static AccountId of(String value) {
        return new AccountId(value);
    }

    @Override
    public String toString() {
        return value;
    }
}