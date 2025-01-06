package dev.hedge.domain.portfolio;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Quantity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
class PositionTest {

    private static final AccountId ACCOUNT = AccountId.of("ACC-1");
    private static final SecurityId SECURITY = SecurityId.of("SEC-1");

    @Test
    @DisplayName("a position joins an account, a security and a quantity")
    void a_position_holds_its_three_parts() {
        Position position = new Position(ACCOUNT, SECURITY, Quantity.of("100"));

        assertThat(position.accountId()).isEqualTo(ACCOUNT);
        assertThat(position.securityId()).isEqualTo(SECURITY);
        assertThat(position.quantity()).isEqualTo(Quantity.of("100"));
    }

    @Test
    @DisplayName("a position without an account is refused")
    void every_part_is_required() {
        assertThatThrownBy(() -> new Position(null, SECURITY, Quantity.of("1")))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("must belong to an account");
        assertThatThrownBy(() -> new Position(ACCOUNT, null, Quantity.of("1")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Position(ACCOUNT, SECURITY, null))
                .isInstanceOf(NullPointerException.class);
    }
}