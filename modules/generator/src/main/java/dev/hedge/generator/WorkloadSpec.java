package dev.hedge.generator;

import java.util.List;
import java.util.Objects;

public record WorkloadSpec(
        long seed,
        int funds,
        int accountsPerFund,
        int positionsPerAccount,
        int securities,
        List<String> currencies) {

    public WorkloadSpec {
        currencies = List.copyOf(Objects.requireNonNull(currencies, "currencies must not be null"));
        requirePositive(funds, "funds");
        requirePositive(accountsPerFund, "accountsPerFund");
        requirePositive(positionsPerAccount, "positionsPerAccount");
        requirePositive(securities, "securities");
        if (currencies.isEmpty()) {
            throw new IllegalArgumentException("at least one currency is required");
        }
        if (positionsPerAccount > securities) {
            throw new IllegalArgumentException("an account cannot hold " + positionsPerAccount
                    + " distinct securities when only " + securities + " exist");
        }
    }

    public static WorkloadSpec tiny(long seed) {
        return new WorkloadSpec(seed, 1, 2, 3, 5, List.of("USD", "EUR"));
    }

    public static WorkloadSpec typical(long seed) {
        return new WorkloadSpec(seed, 20, 4, 25, 200, List.of("USD", "EUR", "GBP", "JPY"));
    }

    public int totalPositions() {
        return funds * accountsPerFund * positionsPerAccount;
    }

    private static void requirePositive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be greater than zero, was " + value);
        }
    }
}