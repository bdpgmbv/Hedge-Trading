package dev.hedge.ingestion.source;

/** Which identifier scheme a provider uses to name securities. */
public enum IdentifierKind {
    CUSIP,
    ISIN,
    SEDOL,
    TICKER,
    /** The provider's own internal code, meaningful to nobody else. */
    PROVIDER_SPECIFIC
}
