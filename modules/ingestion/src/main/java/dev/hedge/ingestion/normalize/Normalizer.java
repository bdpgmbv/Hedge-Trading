package dev.hedge.ingestion.normalize;

import dev.hedge.ingestion.raw.StoredObservation;
import dev.hedge.ingestion.source.SourceDescriptor;

/**
 * Turns one provider's way of writing something into this system's way.
 *
 * <p>Every normalizer carries a {@link #version()}, and that version is stamped onto whatever it
 * produces. Replaying history is only meaningful if the rules that applied then can be identified:
 * re-deriving a year-old position with this year's parsing rules would quietly produce a different
 * answer and call it the same one.
 *
 * @param <T> the canonical but still unresolved shape, before identifiers become ids
 */
public interface Normalizer<T> {

    String version();

    NormalizationResult<T> normalize(StoredObservation observation, SourceDescriptor source);
}
