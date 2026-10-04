package com.parkio.auth.application.durable;

import java.util.List;
import java.util.Optional;

/** Read access to the objects of an evidence store, keyed like the Python model's store. */
public interface EvidenceObjects {

    /** Keys that start with {@code prefix}, in ascending order. */
    List<String> list(String prefix);

    Optional<byte[]> find(String key);

    /**
     * Every stored version of {@code key}; a store without versions returns at most one. Used for
     * the frontier, the only object that is rewritten; the verifier takes its highest verified
     * version.
     */
    default List<byte[]> findAll(String key) {
        return find(key).map(List::of).orElse(List.of());
    }

    default byte[] get(String key) {
        return find(key).orElseThrow(() -> new DurableEvidenceException("missing publication " + key));
    }
}
