package dev.hedge.reconciliation;

import dev.hedge.domain.id.AccountId;
import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.SecurityId;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * One difference between two statements, with enough attached to act on it.
 *
 * <p>Both observation ids are carried so that a break is one join away from the bytes a provider
 * sent. A break somebody cannot trace is a break somebody will close without resolving.
 */
public record Break(
        BreakType type,
        AccountId account,
        SecurityId security,
        Optional<BigDecimal> leftQuantity,
        Optional<BigDecimal> rightQuantity,
        Optional<BigDecimal> difference,
        Optional<ObservationId> leftEvidence,
        Optional<ObservationId> rightEvidence,
        String explanation) {

    public Break {
        Objects.requireNonNull(type, "a break needs a type");
        Objects.requireNonNull(account, "a break needs an account");
        Objects.requireNonNull(security, "a break needs a security");
        Objects.requireNonNull(leftQuantity, "leftQuantity must be present or empty");
        Objects.requireNonNull(rightQuantity, "rightQuantity must be present or empty");
        Objects.requireNonNull(difference, "difference must be present or empty");
        Objects.requireNonNull(leftEvidence, "leftEvidence must be present or empty");
        Objects.requireNonNull(rightEvidence, "rightEvidence must be present or empty");
        Objects.requireNonNull(explanation, "a break needs an explanation");
    }

    public boolean needsAttention() {
        return type != BreakType.MATCH;
    }
}
