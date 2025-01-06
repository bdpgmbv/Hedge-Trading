package dev.hedge.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.FundId;
import dev.hedge.domain.id.SecurityId;
import dev.hedge.domain.money.Money;
import dev.hedge.domain.money.Quantity;
import dev.hedge.domain.portfolio.Account;
import dev.hedge.domain.portfolio.Fund;
import dev.hedge.domain.portfolio.Position;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;          // replaces the HikariDataSource import
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
class FundRepositoryTest {

    private static final Currency USD = Currency.getInstance("USD");
    private static DataSource dataSource;     // was HikariDataSource
    private FundRepository repository;

    @BeforeAll
    static void startDatabase() {
        dataSource = TestDatabase.start(5);
    }

    @AfterAll
    static void stopDatabase() {
        DataSources.close(dataSource);        // was dataSource.close()
    }

    @BeforeEach
    void cleanDatabase() {
        TestDatabase.clear(dataSource);
        repository = new FundRepository(dataSource);
    }

    private static Fund fundWith(String fundId, String accountId, String quantity, String cash) {
        AccountId account = AccountId.of(accountId);
        return new Fund(
                FundId.of(fundId),
                USD,
                List.of(new Account(
                        account,
                        List.of(new Position(account, SecurityId.of("SEC-1"), Quantity.of(quantity))),
                        List.of(Money.of(cash, "USD")))));
    }

    @Test
    @DisplayName("what is saved comes back exactly, with no value changed by the database")
    void a_fund_survives_a_round_trip() {
        Fund original = fundWith("F-1", "ACC-1", "123.45", "9876.54");

        repository.save(original);

        assertThat(repository.findById(FundId.of("F-1"))).contains(original);
    }

    @Test
    @DisplayName("an unknown fund returns nothing rather than failing")
    void an_unknown_fund_is_empty() {
        assertThat(repository.findById(FundId.of("MISSING"))).isEmpty();
    }

    @Test
    @DisplayName("saving the same fund twice is refused by the primary key")
    void a_duplicate_fund_is_refused() {
        Fund fund = fundWith("F-1", "ACC-1", "10", "100.00");
        repository.save(fund);

        assertThatThrownBy(() -> repository.save(fund))
                .isInstanceOf(PersistenceException.class)
                .hasMessageContaining("could not save fund F-1");
    }

    @Test
    @DisplayName("a failed save leaves nothing behind, not even the rows written before the failure")
    void a_failed_save_writes_nothing() {
        AccountId shared = AccountId.of("ACC-1");
        Account first = new Account(
                shared, List.of(new Position(shared, SecurityId.of("SEC-1"), Quantity.of("1"))), List.of());
        Account duplicate = new Account(
                shared, List.of(new Position(shared, SecurityId.of("SEC-2"), Quantity.of("2"))), List.of());
        Fund broken = new Fund(FundId.of("F-BROKEN"), USD, List.of(first, duplicate));

        assertThatThrownBy(() -> repository.save(broken)).isInstanceOf(PersistenceException.class);

        assertThat(repository.findById(FundId.of("F-BROKEN"))).isEmpty();
    }

    @Test
    @DisplayName("an exact decimal amount is stored and returned without loss")
    void money_keeps_its_exact_value() {
        repository.save(fundWith("F-2", "ACC-2", "0.00000001", "0.01"));

        Optional<Fund> loaded = repository.findById(FundId.of("F-2"));

        assertThat(loaded).isPresent();
        Account account = loaded.orElseThrow().accounts().get(0);
        assertThat(account.positions().get(0).quantity()).isEqualTo(Quantity.of("0.00000001"));
        assertThat(account.cashBalances().get(0)).isEqualTo(Money.of("0.01", "USD"));
    }
}