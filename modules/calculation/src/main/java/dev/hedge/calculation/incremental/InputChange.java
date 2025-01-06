package dev.hedge.calculation.incremental;

import dev.hedge.calculation.incremental.CalculationNode.CashInput;
import dev.hedge.calculation.incremental.CalculationNode.PriceInput;
import dev.hedge.calculation.incremental.CalculationNode.QuantityInput;
import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Quantity;
import java.util.Objects;

/**
 * Something the outside world changed. Each change names the single input node it touches, which is
 * the seed the affected closure starts from.
 */
public sealed interface InputChange {

    /** The input node this change invalidates. */
    CalculationNode node();

    record PriceChange(SecurityId security, Money price) implements InputChange {
        public PriceChange {
            Objects.requireNonNull(security, "a price change needs a security");
            Objects.requireNonNull(price, "a price change needs a price");
        }

        @Override
        public CalculationNode node() {
            return new PriceInput(security);
        }
    }

    record QuantityChange(AccountId account, SecurityId security, Quantity quantity)
            implements InputChange {
        public QuantityChange {
            Objects.requireNonNull(account, "a quantity change needs an account");
            Objects.requireNonNull(security, "a quantity change needs a security");
            Objects.requireNonNull(quantity, "a quantity change needs a quantity");
        }

        @Override
        public CalculationNode node() {
            return new QuantityInput(account, security);
        }
    }

    record CashChange(AccountId account, Money amount) implements InputChange {
        public CashChange {
            Objects.requireNonNull(account, "a cash change needs an account");
            Objects.requireNonNull(amount, "a cash change needs an amount");
        }

        @Override
        public CalculationNode node() {
            return new CashInput(account, amount.currency());
        }
    }
}
