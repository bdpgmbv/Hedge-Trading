package dev.hedge.api.dto;

import dev.hedge.domain.portfolio.Fund;
import java.util.List;
public record FundResponse(
        String fundId, String baseCurrency, int positionCount, List<AccountResponse> accounts) {

    public static FundResponse from(Fund fund) {
        return new FundResponse(
                fund.id().value(),
                fund.baseCurrency().getCurrencyCode(),
                fund.positionCount(),
                fund.accounts().stream().map(AccountResponse::from).toList());
    }
}
