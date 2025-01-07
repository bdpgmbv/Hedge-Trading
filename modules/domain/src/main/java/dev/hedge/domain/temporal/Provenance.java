package dev.hedge.domain.temporal;

import dev.hedge.domain.id.ObservationId;
import dev.hedge.domain.id.ProviderId;
import java.util.Objects;

/**
 * Where a value came from. Carried by every canonical value so that any number in the system can be
 * traced back to the bytes a provider actually sent.
 *
 * @param provider who told us
 * @param observation the raw record they told us in
 * @param normalizationVersion the rules that turned that record into a canonical value; needed
 *     because replaying history with today's rules would not reproduce yesterday's answer
 */
public record Provenance(ProviderId provider, ObservationId observation, String normalizationVersion) {

    public Provenance {
        Objects.requireNonNull(provider, "provenance needs a provider");
        Objects.requireNonNull(observation, "provenance needs an observation id");
        if (normalizationVersion == null || normalizationVersion.isBlank()) {
            throw new IllegalArgumentException("provenance needs a normalization version");
        }
        normalizationVersion = normalizationVersion.trim();
    }

    @Override
    public String toString() {
        return provider + "/" + observation + "@" + normalizationVersion;
    }
}
