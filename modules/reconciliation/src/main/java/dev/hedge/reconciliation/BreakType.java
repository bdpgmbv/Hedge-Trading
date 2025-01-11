package dev.hedge.reconciliation;

/**
 * What comparing two statements produced.
 *
 * <p>Deliberately not a boolean. "Matched or not" is useless to whoever has to resolve it: a
 * holding one custodian has never heard of needs a different person doing a different thing than
 * a quantity that differs by 0.0001 from rounding.
 */
public enum BreakType {
    /** Both sides agree, within tolerance. Recorded so that coverage can be measured. */
    MATCH,
    /** The right side has a holding the left side does not. */
    ADDED,
    /** The left side has a holding the right side does not. */
    MISSING,
    /** Both know the holding, the quantities differ beyond tolerance. */
    CHANGED,
    /** The same source restated a value it had already given for the same business moment. */
    CORRECTION,
    /** The same statement arrived more than once and must have no second economic effect. */
    DUPLICATE,
    /** An identifier nobody can map, so the position cannot be compared at all. */
    UNMAPPED,
    /** Two sources of equal standing disagree, and nothing in the data decides which is right. */
    CONFLICT
}
