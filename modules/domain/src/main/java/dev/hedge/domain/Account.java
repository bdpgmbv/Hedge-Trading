package dev.hedge.domain;

import java.util.List;
import java.util.Objects;

public record Account(AccountId id, List<Position> positions, List<Money> cashBalances) {

    public Account {
        Objects.requireNonNull(id, "an account must have an id");
        positions = List.copyOf(Objects.requireNonNull(positions, "positions must not be null"));
        cashBalances = List.copyOf(Objects.requireNonNull(cashBalances, "cash balances must not be null"));
    }

    public static Account empty(AccountId id) {
        return new Account(id, List.of(), List.of());
    }
}