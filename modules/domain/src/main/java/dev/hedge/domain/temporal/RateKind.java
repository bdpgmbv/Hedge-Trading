package dev.hedge.domain.temporal;

/**
 * The two kinds of fx rate, which are not interchangeable.
 *
 * <p>Mixing them is a classic error. If the hedge requirement a desk actually trades on moves
 * every time a tick arrives, nobody can reconcile it with anyone; and valuing a portfolio on a
 * live rate produces a number that was true for a second and is unreproducible thereafter.
 */
public enum RateKind {
    /** Live, moving continuously. Used to watch exposure during the day. */
    MARKET,
    /** One official value struck at a set time. Used for valuation, reports and hedging. */
    FIX
}
