package com.parkio.auth.application.durable;

import java.util.List;
import java.util.Optional;

/** Read access to the objects of an evidence store, keyed like the Python model's store. */
public interface EvidenceObjects {

    /** Keys that start with {@code prefix}, in ascending order. */
    List<String> list(String prefix);

    Optional<byte[]> find(String key);

    default byte[] get(String key) {
        return find(key).orElseThrow(() -> new DurableEvidenceException("missing publication " + key));
    }
}
