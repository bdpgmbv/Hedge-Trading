package dev.hedge.domain.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
class IdentifierTest {

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    @DisplayName("every identifier rejects a blank value, using one shared rule")
    void all_identifiers_reject_blank(String value) {
        assertThatThrownBy(() -> AccountId.of(value)).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> SecurityId.of(value)).hasMessageContaining("must not be blank");
        assertThatThrownBy(() -> FundId.of(value)).hasMessageContaining("must not be blank");
    }

    @Test
    @DisplayName("each identifier names what was wrong, so the message points at the right field")
    void the_message_names_the_identifier() {
        assertThatThrownBy(() -> AccountId.of("")).hasMessageContaining("an account id");
        assertThatThrownBy(() -> SecurityId.of("")).hasMessageContaining("a security id");
        assertThatThrownBy(() -> FundId.of("")).hasMessageContaining("a fund id");
    }

    @Test
    @DisplayName("identifiers with the same text are still different types")
    void identifiers_are_not_interchangeable() {
        assertThat(AccountId.of("X-1").value()).isEqualTo(SecurityId.of("X-1").value());
        assertThat((Object) AccountId.of("X-1")).isNotEqualTo(SecurityId.of("X-1"));
    }
}