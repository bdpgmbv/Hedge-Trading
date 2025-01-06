package dev.hedge.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.RoundingMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
class FxRateTest {

    @Test
    @DisplayName("a rate converts money in its base currency into its quote currency")
    void conversion_works_in_the_declared_direction() {
        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");

        assertThat(eurUsd.convert(Money.of("100.00", "EUR"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("110.00", "USD"));
    }

    @Test
    @DisplayName("converting in the wrong direction is refused, it does not silently return a wrong number")
    void the_wrong_direction_is_refused() {
        FxRate eurUsd = FxRate.of("EUR", "USD", "1.10");

        assertThatThrownBy(() -> eurUsd.convert(Money.of("100.00", "USD"), RoundingMode.HALF_UP))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("converts EUR to USD, but was given USD");
    }

    @Test
    @DisplayName("the inverse rate converts the other way")
    void inverse_converts_the_other_way() {
        FxRate usdEur = FxRate.of("EUR", "USD", "1.25").inverse();

        assertThat(usdEur.base().getCurrencyCode()).isEqualTo("USD");
        assertThat(usdEur.quote().getCurrencyCode()).isEqualTo("EUR");
        assertThat(usdEur.convert(Money.of("125.00", "USD"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    @DisplayName("a zero or negative rate is refused")
    void a_rate_must_be_positive() {
        assertThatThrownBy(() -> FxRate.of("EUR", "USD", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> FxRate.of("EUR", "USD", "-1.10"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a rate between a currency and itself is refused")
    void base_and_quote_must_differ() {
        assertThatThrownBy(() -> FxRate.of("USD", "USD", "1.00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    @DisplayName("converting into a zero decimal currency rounds to whole units")
    void conversion_uses_the_target_currency_decimals() {
        FxRate usdJpy = FxRate.of("USD", "JPY", "151.37");

        assertThat(usdJpy.convert(Money.of("10.00", "USD"), RoundingMode.HALF_UP))
                .isEqualTo(Money.of("1514", "JPY"));
    }
}