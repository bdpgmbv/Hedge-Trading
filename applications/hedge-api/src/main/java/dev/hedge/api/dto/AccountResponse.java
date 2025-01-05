package dev.hedge.api.dto;

import dev.hedge.domain.Account;
import java.util.List;

public record AccountResponse(
        String accountId, List<PositionResponse> positions, List<MoneyResponse> cash) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.id().value(),
                account.positions().stream().map(PositionResponse::from).toList(),
                account.cashBalances().stream().map(MoneyResponse::from).toList());
    }
}
