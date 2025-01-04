package dev.hedge.api;

import dev.hedge.domain.Account;
import dev.hedge.domain.Fund;
import dev.hedge.domain.Money;
import dev.hedge.domain.Position;
import java.util.List;

final class ApiResponses {

    private ApiResponses() {}

    record MoneyResponse(String amount, String currency) {
        static MoneyResponse from(Money money) {
            return new MoneyResponse(money.amount().toPlainString(), money.currency().getCurrencyCode());
        }
    }

    record PositionResponse(String securityId, String quantity) {
        static PositionResponse from(Position position) {
            return new PositionResponse(position.securityId().value(), position.quantity().value().toPlainString());
        }
    }

    record AccountResponse(String accountId, List<PositionResponse> positions, List<MoneyResponse> cash) {
        static AccountResponse from(Account account) {
            return new AccountResponse(
                    account.id().value(),
                    account.positions().stream().map(PositionResponse::from).toList(),
                    account.cashBalances().stream().map(MoneyResponse::from).toList());
        }
    }

    record FundResponse(
            String fundId, String baseCurrency, int positionCount, List<AccountResponse> accounts) {
        static FundResponse from(Fund fund) {
            return new FundResponse(
                    fund.id().value(),
                    fund.baseCurrency().getCurrencyCode(),
                    fund.positionCount(),
                    fund.accounts().stream().map(AccountResponse::from).toList());
        }
    }

    record PageResponse<T>(List<T> items, int page, int size, long total) {}
}