package dev.hedge.domain;

import java.util.Currency;
import java.util.List;
import java.util.Objects;

public record Fund(FundId id, Currency baseCurrency, List<Account> accounts) {

    public Fund {
        Objects.requireNonNull(id, "a fund must have an id");
        Objects.requireNonNull(baseCurrency, "a fund must have a base currency");
        accounts = List.copyOf(Objects.requireNonNull(accounts, "accounts must not be null"));
    }

    public int positionCount() {
        return accounts.stream().mapToInt(account -> account.positions().size()).sum();
    }
}