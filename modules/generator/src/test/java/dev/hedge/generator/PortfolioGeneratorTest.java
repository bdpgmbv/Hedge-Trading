package dev.hedge.generator;

import static org.assertj.core.api.Assertions.assertThat;

import dev.hedge.domain.Account;
import dev.hedge.domain.Fund;
import dev.hedge.domain.Position;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PortfolioGeneratorTest {

    private final PortfolioGenerator generator = new PortfolioGenerator();

    @Test
    @DisplayName("the same seed produces the same portfolio, so a failure can be reproduced exactly")
    void generation_is_deterministic() {
        WorkloadSpec spec = WorkloadSpec.tiny(42);

        assertThat(generator.generate(spec)).isEqualTo(generator.generate(spec));
    }

    @Test
    @DisplayName("a different seed produces a different portfolio")
    void a_different_seed_changes_the_data() {
        assertThat(generator.generate(WorkloadSpec.tiny(42)))
                .isNotEqualTo(generator.generate(WorkloadSpec.tiny(43)));
    }

    @Test
    @DisplayName("one fund is identical whether one fund or many are generated")
    void each_fund_is_generated_independently() {
        WorkloadSpec one = new WorkloadSpec(42, 1, 2, 3, 5, List.of("USD", "EUR"));
        WorkloadSpec many = new WorkloadSpec(42, 10, 2, 3, 5, List.of("USD", "EUR"));

        Fund fromSmall = generator.generate(one).funds().get(0);
        Fund fromLarge = generator.generate(many).funds().get(0);

        assertThat(fromSmall).isEqualTo(fromLarge);
    }

    @Test
    @DisplayName("every position refers to a security that has a price")
    void every_position_has_a_price() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(7));

        for (Fund fund : portfolio.funds()) {
            for (Account account : fund.accounts()) {
                for (Position position : account.positions()) {
                    assertThat(portfolio.prices()).containsKey(position.securityId());
                }
            }
        }
    }

    @Test
    @DisplayName("every position belongs to the account that holds it")
    void positions_belong_to_their_account() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(7));

        for (Fund fund : portfolio.funds()) {
            for (Account account : fund.accounts()) {
                assertThat(account.positions())
                        .allSatisfy(position -> assertThat(position.accountId()).isEqualTo(account.id()));
            }
        }
    }

    @Test
    @DisplayName("the generated size matches what the specification asked for")
    void the_size_matches_the_specification() {
        WorkloadSpec spec = WorkloadSpec.typical(1);

        assertThat(generator.generate(spec).positionCount()).isEqualTo(spec.totalPositions());
        assertThat(spec.totalPositions()).isEqualTo(20 * 4 * 25);
    }

    @Test
    @DisplayName("a currency with no decimal part is generated correctly")
    void zero_decimal_currencies_are_supported() {
        WorkloadSpec spec = new WorkloadSpec(5, 2, 2, 2, 4, List.of("JPY", "USD"));

        GeneratedPortfolio portfolio = generator.generate(spec);

        assertThat(portfolio.prices().values())
                .allSatisfy(price -> assertThat(price.amount().scale()).isZero());
    }

    @Test
    @DisplayName("one rate is produced for every currency other than the base")
    void a_rate_exists_for_each_non_base_currency() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(1));

        assertThat(portfolio.rates()).hasSize(3);
        assertThat(portfolio.rates())
                .allSatisfy(rate -> assertThat(rate.quote().getCurrencyCode()).isEqualTo("USD"));
    }

    @Test
    @DisplayName("an account never holds the same security twice")
    void securities_are_distinct_within_an_account() {
        GeneratedPortfolio portfolio = generator.generate(WorkloadSpec.typical(99));

        for (Fund fund : portfolio.funds()) {
            for (Account account : fund.accounts()) {
                assertThat(account.positions())
                        .extracting(Position::securityId)
                        .doesNotHaveDuplicates();
            }
        }
    }
}