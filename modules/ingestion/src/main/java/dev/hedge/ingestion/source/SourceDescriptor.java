package dev.hedge.ingestion.source;

import dev.hedge.domain.id.ProviderId;
import dev.hedge.domain.temporal.ObservationKind;
import java.util.Objects;
import java.util.Set;

/**
 * What a provider is and, more importantly, what it is permitted to tell us.
 *
 * @param provides the kinds this provider may supply. A custodian supplies POSITION only; a market
 *     data vendor supplies PRICE and FX_RATE. Anything else a file happens to contain is
 *     quarantined rather than stored, so the separation is enforced by the system rather than by a
 *     comment someone will eventually ignore.
 */
public record SourceDescriptor(
        ProviderId provider,
        Set<ObservationKind> provides,
        IdentifierKind identifierKind,
        DeliveryStyle deliveryStyle,
        String format) {

    public SourceDescriptor {
        Objects.requireNonNull(provider, "a source needs a provider id");
        Objects.requireNonNull(identifierKind, "a source needs an identifier kind");
        Objects.requireNonNull(deliveryStyle, "a source needs a delivery style");
        provides = Set.copyOf(Objects.requireNonNull(provides, "a source must declare what it provides"));
        if (provides.isEmpty()) {
            throw new IllegalArgumentException("source " + provider + " declares no observation kinds");
        }
        if (format == null || format.isBlank()) {
            throw new IllegalArgumentException("a source needs a format name");
        }
        format = format.trim();
    }

    public boolean mayProvide(ObservationKind kind) {
        return provides.contains(Objects.requireNonNull(kind, "a kind is required"));
    }
}
