package dev.hedge.domain.portfolio;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import java.util.Objects;
public record Position(AccountId accountId, SecurityId securityId, Quantity quantity) {

    public Position {
        Objects.requireNonNull(accountId, "a position must belong to an account");
        Objects.requireNonNull(securityId, "a position must refer to a security");
        Objects.requireNonNull(quantity, "a position must have a quantity");
    }
}