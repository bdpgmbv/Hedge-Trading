package dev.hedge.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
class FundIdTest {

    @Test
    @DisplayName("a fund id keeps the value it was given")
    void a_valid_identifier_is_kept() {
        assertThat(FundId.of("FUND-00001").value()).isEqualTo("FUND-00001");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t", "\n"})
    @DisplayName("a blank fund id is rejected at construction, so no later layer has to check for it")
    void a_blank_identifier_is_rejected(String value) {
        assertThatThrownBy(() -> FundId.of(value))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("surrounding whitespace is removed, so one fund cannot become two keys")
    void the_identifier_is_trimmed() {
        assertThat(FundId.of("  FUND-00001  ")).isEqualTo(FundId.of("FUND-00001"));
    }

    @Test
    @DisplayName("two fund ids with the same value are the same identity")
    void equality_is_by_value() {
        assertThat(FundId.of("FUND-00001"))
                .isEqualTo(FundId.of("FUND-00001"))
                .hasSameHashCodeAs(FundId.of("FUND-00001"))
                .isNotEqualTo(FundId.of("FUND-00002"));
    }
}