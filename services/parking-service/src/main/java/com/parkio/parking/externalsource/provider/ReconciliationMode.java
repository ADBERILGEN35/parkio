package com.parkio.parking.externalsource.provider;

/**
 * Missing-set soft-deactivation policy for a source feed.
 *
 * <p>{@link #AUTHORITATIVE_FULL_SET} may deactivate unseen links only after a fully
 * successful, non-empty, duplicate-free accepted set. {@link #UPSERT_ONLY} never
 * mass-deactivates from this live-adapter path (file importers may still reconcile
 * explicitly in their own orchestration).
 */
public enum ReconciliationMode {
    AUTHORITATIVE_FULL_SET,
    UPSERT_ONLY
}
