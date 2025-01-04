package dev.hedge.domain;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record Account(AccountId id, List<Position> positions, List<Money> cashBalances) {

    public Account {
        Objects.requireNonNull(id, "an account must have an id");
        Objects.requireNonNull(positions, "positions must not be null");
        Objects.requireNonNull(cashBalances, "cash balances must not be null");
        requireOnePositionPerSecurity(id, positions);
        requireOneBalancePerCurrency(id, cashBalances);
        positions = positions.stream()
                .sorted(Comparator.comparing(position -> position.securityId().value()))
                .toList();
        cashBalances = cashBalances.stream()
                .sorted(Comparator.comparing(balance -> balance.currency().getCurrencyCode()))
                .toList();
    }

    public static Account empty(AccountId id) {
        return new Account(id, List.of(), List.of());
    }

    private static void requireOnePositionPerSecurity(AccountId id, List<Position> positions) {
        Set<SecurityId> seen = new HashSet<>();
        for (Position position : positions) {
            if (!seen.add(position.securityId())) {
                throw new IllegalArgumentException("account " + id + " holds " + position.securityId()
                        + " twice; a position is the whole holding of one security, so there can be only one");
            }
        }
    }

    private static void requireOneBalancePerCurrency(AccountId id, List<Money> cashBalances) {
        Set<String> seen = new HashSet<>();
        for (Money balance : cashBalances) {
            if (!seen.add(balance.currency().getCurrencyCode())) {
                throw new IllegalArgumentException("account " + id + " holds "
                        + balance.currency().getCurrencyCode() + " cash twice");
            }
        }
    }
}