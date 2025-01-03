package dev.hedge.generator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class WorkloadSpecTest {

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("a size of zero or less is refused, so an empty workload cannot be measured by mistake")
    void sizes_must_be_positive(int bad) {
        assertThatThrownBy(() -> new WorkloadSpec(1, bad, 1, 1, 1, List.of("USD")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("funds must be greater than zero");
    }

    @Test
    @DisplayName("a workload with no currency is refused")
    void at_least_one_currency_is_required() {
        assertThatThrownBy(() -> new WorkloadSpec(1, 1, 1, 1, 1, List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one currency");
    }

    @Test
    @DisplayName("changing the caller's list afterwards does not change the specification")
    void the_specification_copies_its_currencies() {
        List<String> mutable = new ArrayList<>(List.of("USD", "EUR"));
        WorkloadSpec spec = new WorkloadSpec(1, 1, 1, 1, 1, mutable);

        mutable.add("GBP");

        assertThat(spec.currencies()).containsExactly("USD", "EUR");
    }

    @Test
    @DisplayName("the expected position count can be calculated before generating anything")
    void the_size_is_known_in_advance() {
        assertThat(WorkloadSpec.tiny(1).totalPositions()).isEqualTo(6);
        assertThat(WorkloadSpec.typical(1).totalPositions()).isEqualTo(2000);
    }
}