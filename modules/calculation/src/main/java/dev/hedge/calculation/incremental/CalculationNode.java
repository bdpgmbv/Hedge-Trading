package dev.hedge.calculation.incremental;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ForwardId;
import dev.hedge.domain.id.SecurityId;
import java.util.Currency;
import java.util.Objects;

/**
 * One addressable step of the calculation. The type hierarchy carries a rule the compiler can
 * check: only an {@link Input} can be changed from outside, and only a {@link Derived} node is ever
 * recomputed. A node is a value, so two nodes naming the same step are the same node, which is what
 * lets the graph deduplicate work.
 */
public sealed interface CalculationNode {

    /** A stable, readable identity used for deterministic ordering and for debugging. */
    String key();

    /** Something the outside world can change: a price, a rate, a holding, cash, a forward leg. */
    sealed interface Input extends CalculationNode {}

    /** Something computed from other nodes. */
    sealed interface Derived extends CalculationNode {}

    record PriceInput(SecurityId security) implements Input {
        public PriceInput {
            Objects.requireNonNull(security, "a price input needs a security");
        }

        @Override
        public String key() {
            return "price:" + security.value();
        }
    }

    record RateInput(Currency from, Currency to) implements Input {
        public RateInput {
            Objects.requireNonNull(from, "a rate input needs a source currency");
            Objects.requireNonNull(to, "a rate input needs a target currency");
            if (from.equals(to)) {
                throw new IllegalArgumentException(
                        "a rate input must convert between two different currencies, both were "
                                + from.getCurrencyCode());
            }
        }

        @Override
        public String key() {
            return "rate:" + from.getCurrencyCode() + ">" + to.getCurrencyCode();
        }
    }

    record QuantityInput(AccountId account, SecurityId security) implements Input {
        public QuantityInput {
            Objects.requireNonNull(account, "a quantity input needs an account");
            Objects.requireNonNull(security, "a quantity input needs a security");
        }

        @Override
        public String key() {
            return "qty:" + account.value() + ":" + security.value();
        }
    }

    record CashInput(AccountId account, Currency currency) implements Input {
        public CashInput {
            Objects.requireNonNull(account, "a cash input needs an account");
            Objects.requireNonNull(currency, "a cash input needs a currency");
        }

        @Override
        public String key() {
            return "cash:" + account.value() + ":" + currency.getCurrencyCode();
        }
    }

    record ForwardInput(ForwardId forward, Currency currency) implements Input {
        public ForwardInput {
            Objects.requireNonNull(forward, "a forward input needs a forward id");
            Objects.requireNonNull(currency, "a forward input needs a currency");
        }

        @Override
        public String key() {
            return "fwd:" + forward.value() + ":" + currency.getCurrencyCode();
        }
    }

    record PositionValue(AccountId account, SecurityId security) implements Derived {
        public PositionValue {
            Objects.requireNonNull(account, "a position value needs an account");
            Objects.requireNonNull(security, "a position value needs a security");
        }

        @Override
        public String key() {
            return "posval:" + account.value() + ":" + security.value();
        }
    }

    record CurrencyTotal(Currency currency) implements Derived {
        public CurrencyTotal {
            Objects.requireNonNull(currency, "a currency total needs a currency");
        }

        @Override
        public String key() {
            return "ccy:" + currency.getCurrencyCode();
        }
    }

    record HedgeTarget(Currency currency) implements Derived {
        public HedgeTarget {
            Objects.requireNonNull(currency, "a hedge target needs a currency");
        }

        @Override
        public String key() {
            return "hedge:" + currency.getCurrencyCode();
        }
    }

    record PortfolioTotal() implements Derived {
        @Override
        public String key() {
            return "total";
        }
    }
}
