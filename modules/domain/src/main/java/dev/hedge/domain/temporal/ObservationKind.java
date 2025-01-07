package dev.hedge.domain.temporal;

/**
 * What a source is allowed to tell us. Custodians report what you hold; market data vendors report
 * what things are worth. Keeping them apart is not tidiness: if every custodian priced its own
 * holdings, the same security would carry a different value depending on who held it, and exposure
 * figures would stop being comparable across the fund.
 */
public enum ObservationKind {
    POSITION,
    PRICE,
    FX_RATE
}
