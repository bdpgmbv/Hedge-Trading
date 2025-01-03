package dev.hedge.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class FundTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static final AccountId ACCOUNT = AccountId.of("ACC-1");

    @Test
    @DisplayName("changing the caller's list afterwards does not change the account")
    void an_account_copies_its_positions() {
        List<Position> mutable = new ArrayList<>();
        mutable.add(new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("10")));

        Account account = new Account(ACCOUNT, mutable, List.of());
        mutable.add(new Position(ACCOUNT, SecurityId.of("S-2"), Quantity.of("20")));

        assertThat(account.positions()).hasSize(1);
    }

    @Test
    @DisplayName("the list inside an account cannot be changed either")
    void the_stored_list_is_read_only() {
        Account account = Account.empty(ACCOUNT);

        assertThatThrownBy(() -> account.positions().add(
                new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("1"))))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("a fund counts the positions held across all of its accounts")
    void a_fund_counts_its_positions() {
        Account first = new Account(ACCOUNT, List.of(
                new Position(ACCOUNT, SecurityId.of("S-1"), Quantity.of("1")),
                new Position(ACCOUNT, SecurityId.of("S-2"), Quantity.of("2"))), List.of());
        AccountId second = AccountId.of("ACC-2");
        Account other = new Account(second, List.of(
                new Position(second, SecurityId.of("S-3"), Quantity.of("3"))), List.of());

        Fund fund = new Fund(FundId.of("F-1"), USD, List.of(first, other));

        assertThat(fund.positionCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("a fund must have a base currency, because its exposure is measured against one")
    void a_fund_requires_a_base_currency() {
        assertThatThrownBy(() -> new Fund(FundId.of("F-1"), null, List.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("base currency");
    }
}