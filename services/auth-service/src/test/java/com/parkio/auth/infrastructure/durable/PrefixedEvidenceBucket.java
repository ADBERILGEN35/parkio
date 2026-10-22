package com.parkio.auth.infrastructure.durable;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Confines a {@link DurableEvidenceBucket} to one run's namespace, so repeated #483 acceptance runs
 * in the same test bucket never read each other's evidence. Keys are prefixed on the way in and
 * the prefix is removed on the way out; everything else is delegated unchanged, so the store under
 * test still sees canonical evidence keys ({@code records/}, {@code sequences/}, ...).
 */
final class PrefixedEvidenceBucket implements DurableEvidenceBucket {

    private final DurableEvidenceBucket delegate;
    private final String prefix;

    PrefixedEvidenceBucket(DurableEvidenceBucket delegate, String prefix) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        if (prefix == null || prefix.isEmpty() || !prefix.endsWith("/")) {
            throw new IllegalArgumentException("prefix must be non-empty and end with '/'");
        }
        this.prefix = prefix;
    }

    String prefix() {
        return prefix;
    }

    @Override
    public String bucket() {
        return delegate.bucket();
    }

    @Override
    public void requireRetentionFeature() {
        delegate.requireRetentionFeature();
    }

    @Override
    public List<String> keys(String keyPrefix) {
        return delegate.keys(prefix + keyPrefix).stream().map(this::strip).toList();
    }

    @Override
    public Optional<StoredVersion> oldest(String key) {
        return delegate.oldest(prefix + key).map(this::strip);
    }

    @Override
    public List<StoredVersion> allVersions(String key) {
        return delegate.allVersions(prefix + key).stream().map(this::strip).toList();
    }

    @Override
    public int versionCount(String key) {
        return delegate.versionCount(prefix + key);
    }

    @Override
    public String put(String key, byte[] bytes, EvidenceRetentionMode mode, ZonedDateTime retainUntil) {
        return delegate.put(prefix + key, bytes, mode, retainUntil);
    }

    @Override
    public RetentionLock retention(String key, String versionId) {
        return delegate.retention(prefix + key, versionId);
    }

    private StoredVersion strip(StoredVersion version) {
        return new StoredVersion(strip(version.key()), version.versionId(), version.bytes());
    }

    private String strip(String key) {
        if (!key.startsWith(prefix)) {
            throw new IllegalStateException("listed key is outside the run namespace");
        }
        return key.substring(prefix.length());
    }
}
