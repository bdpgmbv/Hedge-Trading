package dev.hedge.ingestion.raw;

public enum BatchStatus {
    /** Parsed, complete, and stored as evidence. */
    ACCEPTED,
    /** Stored, but the trailer count disagreed with what was parsed. Not safe to interpret. */
    INCOMPLETE,
    /** Stored, but the provider sent a kind of observation it is not permitted to supply. */
    QUARANTINED
}
