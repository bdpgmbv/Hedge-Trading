package dev.hedge.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
class MoneyTest {

    @Test
    @DisplayName("decimal arithmetic is exact, floating point is not")
    void floating_point_cannot_represent_money_exactly() {
        assertThat(0.1 + 0.2).isNotEqualTo(0.3);

        assertThat(Money.of("0.10", "USD").plus(Money.of("0.20", "USD")))
                .isEqualTo(Money.of("0.30", "USD"));
    }

    @Test
    @DisplayName("an amount is stored at the number of decimals its currency uses")
    void the_scale_comes_from_the_currency() {
        assertThat(Money.of("100", "USD").amount().scale()).isEqualTo(2);
        assertThat(Money.of("100", "JPY").amount().scale()).isZero();
    }

    @Test
    @DisplayName("the same amount written differently is the same money")
    void amounts_are_normalised() {
        assertThat(Money.of("100", "USD")).isEqualTo(Money.of("100.00", "USD"));
    }

    @Test
    @DisplayName("an amount with more decimals than the currency allows is refused, never silently rounded")
    void extra_decimals_are_refused() {
        assertThatThrownBy(() -> Money.of("1.005", "USD"))
                .isInstanceOf(ArithmeticException.class);

        assertThatThrownBy(() -> Money.of("100.5", "JPY"))
                .isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("two different currencies cannot be added")
    void currencies_cannot_be_mixed() {
        assertThatThrownBy(() -> Money.of("100.00", "USD").plus(Money.of("100.00", "EUR")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot combine USD with EUR");
    }

    @Test
    @DisplayName("the same currency adds and subtracts normally")
    void same_currency_arithmetic_works() {
        assertThat(Money.of("100.00", "USD").plus(Money.of("25.50", "USD")))
                .isEqualTo(Money.of("125.50", "USD"));
        assertThat(Money.of("100.00", "USD").minus(Money.of("125.50", "USD")))
                .isEqualTo(Money.of("-25.50", "USD"));
    }

    @Test
    @DisplayName("multiplication requires the caller to choose how to round")
    void rounding_is_chosen_by_the_caller() {
        Money price = Money.of("10.00", "USD");

        assertThat(price.multipliedBy(new BigDecimal("1.0049"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("10.05", "USD"));
        assertThat(price.multipliedBy(new BigDecimal("1.0049"), RoundingMode.DOWN))
                .isEqualTo(Money.of("10.04", "USD"));
    }

    @Test
    @DisplayName("an unknown currency code is refused")
    void unknown_currency_is_refused() {
        assertThatThrownBy(() -> Money.of("1.00", "XXXX"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a negative amount is allowed, because an account can be overdrawn")
    void negative_amounts_are_allowed() {
        assertThat(Money.of("-5.00", "USD").isNegative()).isTrue();
        assertThat(Money.zero("USD").isZero()).isTrue();
    }
}