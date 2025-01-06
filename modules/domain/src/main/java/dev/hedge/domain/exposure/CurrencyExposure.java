package dev.hedge.domain.exposure;

import dev.hedge.domain.money.Money;
import java.util.Currency;
import java.util.Objects;
public record CurrencyExposure(
        Currency currency, Money securities, Money cash, Money forwards, Money gross, Money net) {

    public CurrencyExposure {
        Objects.requireNonNull(currency, "an exposure must have a currency");
        requireIn(currency, securities, "securities");
        requireIn(currency, cash, "cash");
        requireIn(currency, forwards, "forwards");
        requireIn(currency, gross, "gross");
        requireIn(currency, net, "net");
    }

    /** Derives gross (securities + cash) and net (gross + forwards) so they cannot disagree. */
    public static CurrencyExposure of(Money securities, Money cash, Money forwards) {
        Objects.requireNonNull(securities, "securities must not be null");
        Objects.requireNonNull(cash, "cash must not be null");
        Objects.requireNonNull(forwards, "forwards must not be null");
        Money gross = securities.plus(cash);
        return new CurrencyExposure(
                securities.currency(), securities, cash, forwards, gross, gross.plus(forwards));
    }

    private static void requireIn(Currency currency, Money amount, String name) {
        Objects.requireNonNull(amount, name + " must not be null");
        if (!amount.currency().equals(currency)) {
            throw new IllegalArgumentException(name + " must be in " + currency.getCurrencyCode()
                    + ", was " + amount.currency().getCurrencyCode());
        }
    }
}