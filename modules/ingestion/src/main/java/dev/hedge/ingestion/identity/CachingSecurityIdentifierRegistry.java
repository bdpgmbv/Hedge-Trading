package dev.hedge.ingestion.identity;

import dev.hedge.domain.id.SecurityId;
import dev.hedge.ingestion.source.IdentifierKind;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The same registry with the round trips removed.
 *
 * <p>Measured before this existed: 23,980 lookups a second, against 2.4 million records a second
 * for parsing. Identity resolution was a hundred times slower than the stage everyone assumes
 * dominates, and the cause was not the query — it was doing one network round trip per record for
 * an answer that almost never changes within a batch.
 *
 * <p>Reference data changes on the scale of days; a batch is read in seconds. Caching for the life
 * of a batch is therefore safe, and {@link #map} clears the entry it affects so a newly supplied
 * mapping takes effect immediately rather than after a restart.
 */
public final class CachingSecurityIdentifierRegistry {

    private record Key(IdentifierKind kind, String identifier) {}

    private final SecurityIdentifierRegistry delegate;
    private final Map<Key, Resolution<SecurityId>> cache = new ConcurrentHashMap<>();

    public CachingSecurityIdentifierRegistry(SecurityIdentifierRegistry delegate) {
        this.delegate = Objects.requireNonNull(delegate, "a registry is required");
    }

    public Resolution<SecurityId> resolve(IdentifierKind kind, String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return delegate.resolve(kind, identifier);
        }
        return cache.computeIfAbsent(
                new Key(kind, identifier.trim()), key -> delegate.resolve(key.kind(), key.identifier()));
    }

    /**
     * Supplying a mapping invalidates the entry for it. Misses are cached too — an unmapped
     * identifier is looked up once per batch rather than once per row — so without this a newly
     * mapped security would stay invisible until the process restarted.
     */
    public void map(IdentifierKind kind, String identifier, SecurityId security, String assertedBy) {
        delegate.map(kind, identifier, security, assertedBy);
        cache.remove(new Key(kind, identifier.trim()));
    }

    public List<String> namesOf(SecurityId security) {
        return delegate.namesOf(security);
    }

    public void clear() {
        cache.clear();
    }

    public int cachedEntries() {
        return cache.size();
    }
}
