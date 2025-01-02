package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class QuantityTest {

    @Test
    @DisplayName("the same quantity written differently is the same quantity")
    void trailing_zeros_do_not_create_a_second_quantity() {
        assertThat(Quantity.of("100")).isEqualTo(Quantity.of("100.00"));
    }

    @Test
    @DisplayName("a negative quantity is a short position, not an error")
    void negative_means_short() {
        assertThat(Quantity.of("-500").isShort()).isTrue();
        assertThat(Quantity.of("500").isShort()).isFalse();
        assertThat(Quantity.of("0").isZero()).isTrue();
    }

    @Test
    @DisplayName("a quantity keeps its fractional part, because some instruments trade in fractions")
    void fractional_quantities_are_kept() {
        assertThat(Quantity.of("0.5").toString()).isEqualTo("0.5");
    }

    @Test
    @DisplayName("a missing quantity is refused")
    void null_is_refused() {
        assertThatThrownBy(() -> new Quantity(null)).isInstanceOf(NullPointerException.class);
    }
}