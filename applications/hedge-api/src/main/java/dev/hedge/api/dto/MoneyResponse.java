package dev.hedge.api.dto;

import dev.hedge.domain.Money;

public record MoneyResponse(String amount, String currency) {

    public static MoneyResponse from(Money money) {
        return new MoneyResponse(money.amount().toPlainString(), money.currency().getCurrencyCode());
    }
}
