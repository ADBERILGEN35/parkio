package com.parkio.auth.application.durable;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.RecoveryVerdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.Verdict;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedFrontier;
import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedPending;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The erasure set an isolated recovery replays: the latest trusted checkpoint's entries plus the
 * trusted pending records with a higher sequence (docs/operations/recovery-evidence-contract.md,
 * Checkpoints). Built only from an {@link Verdict#ACCEPT_ISOLATED} recovery of a bundle, whose
 * frontier is the highest verified version and has no gap. The same computation, checks and
 * messages as {@code trusted_erasure_set} in {@code scripts/lib/recovery_evidence_bundle.py}; the
 * shared fixtures in {@code durable-erasure-evidence/v2/bundles} pin both.
 *
 * <p>Coverage is reported only as the verified sequence ({@link #statement()}). Format v2 signs no
 * time, so nothing here claims time-based coverage or that no later erasure exists.
 *
 * @param verifiedThroughSequence the signed frontier's {@code expectedThrough}
 * @param frontierVersion the store version id of the frontier version that set it
 * @param latestTrustedCheckpoint the checkpoint the set starts from, or {@code null}
 * @param ignoredFrontierVersions frontier versions that failed verification (tamper evidence)
 * @param entries one entry per user, ordered by {@code authUserId} text
 */
public record TrustedErasureSet(long verifiedThroughSequence,
                                String frontierVersion,
                                Long latestTrustedCheckpoint,
                                int ignoredFrontierVersions,
                                String erasureSetDigest,
                                List<ErasureLedgerEntry> entries) {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Comparator<VerifiedFrontier> FRONTIER_ORDER = Comparator
            .comparingLong(VerifiedFrontier::expectedThrough)
            .thenComparingLong(VerifiedFrontier::highestReserved);

    public TrustedErasureSet {
        Objects.requireNonNull(frontierVersion, "frontierVersion");
        Objects.requireNonNull(erasureSetDigest, "erasureSetDigest");
        entries = List.copyOf(entries);
    }

    /** The only coverage wording recovery reports. */
    public static String statement(long verifiedThroughSequence, String frontierVersion) {
        return "erasure coverage verified through sequence " + verifiedThroughSequence
                + " (frontier version " + frontierVersion + ")";
    }

    public String statement() {
        return statement(verifiedThroughSequence, frontierVersion);
    }

    /**
     * The trusted set of {@code bundle}, or {@link DurableEvidenceException} when the evidence is not
     * trusted: no frontier, a gap, an invalid object (also one under another object's key, or a
     * sequence with two owners), a checkpoint ledger naming one user twice, a pending record below
     * the checkpoint that its ledger lacks, or one user with two erasure times.
     */
    public static TrustedErasureSet extract(EvidenceBundle bundle, DurableErasureEvidenceVerifier verifier) {
        RecoveryVerdict recovered = verifier.recover(bundle, null);
        if (recovered.verdict() != Verdict.ACCEPT_ISOLATED) {
            throw new DurableEvidenceException(recovered.verdict() + ": " + recovered.reason() + "; recovery BLOCKED");
        }
        long expectedThrough = recovered.expectedThrough();
        Long checkpointSequence = recovered.latestTrustedCheckpoint();
        Map<String, String> ledger = new TreeMap<>();
        if (checkpointSequence != null) {
            byte[] raw = bundle.get(DurableErasureEvidence.checkpointKey(checkpointSequence));
            if (verifier.verifyCheckpoint(raw).sequence() != checkpointSequence) {
                throw new DurableEvidenceException("the latest trusted checkpoint's body is not that checkpoint");
            }
            for (JsonNode entry : read(raw).path("entries")) {
                if (ledger.put(entry.path("authUserId").asText(), entry.path("erasedAt").asText()) != null) {
                    throw new DurableEvidenceException("duplicate user in the checkpoint ledger");
                }
            }
        }
        Map<String, String> entries = new TreeMap<>(ledger);
        for (VerifiedPending record : recovered.pending()) {
            if (checkpointSequence != null && record.sequence() < checkpointSequence) {
                if (!record.erasedAt().equals(ledger.get(record.authUserId()))) {
                    throw new DurableEvidenceException("a pending record below the latest checkpoint is not in its ledger");
                }
                continue;
            }
            String prior = entries.get(record.authUserId());
            if (prior != null && !prior.equals(record.erasedAt())) {
                throw new DurableEvidenceException("conflicting erasedAt for one user in the trusted erasure set");
            }
            entries.put(record.authUserId(), record.erasedAt());
        }
        List<ErasureLedgerEntry> ordered = new ArrayList<>();
        entries.forEach((user, erasedAt) -> ordered.add(entry(user, erasedAt)));
        return new TrustedErasureSet(expectedThrough, frontierVersionId(bundle, verifier), checkpointSequence,
                recovered.ignoredFrontierVersions(), ErasureSetDigest.of(ordered), ordered);
    }

    /** The id of the first listed frontier version that verifies to the highest boundary. */
    private static String frontierVersionId(EvidenceBundle bundle, DurableErasureEvidenceVerifier verifier) {
        String highestId = null;
        VerifiedFrontier highest = null;
        for (EvidenceBundle.FrontierVersion version : bundle.frontierVersions()) {
            Optional<VerifiedFrontier> verified;
            try {
                verified = verifier.verifyFrontier(Optional.of(version.bytes()));
            } catch (DurableEvidenceException ex) {
                continue;
            }
            if (verified.isPresent() && (highest == null || FRONTIER_ORDER.compare(verified.get(), highest) > 0)) {
                highest = verified.get();
                highestId = version.versionId();
            }
        }
        if (highestId == null) {
            throw new DurableEvidenceException("no frontier version verifies");
        }
        return highestId;
    }

    private static ErasureLedgerEntry entry(String authUserId, String erasedAt) {
        ErasureLedgerEntry entry;
        try {
            entry = new ErasureLedgerEntry(UUID.fromString(authUserId), Instant.parse(erasedAt));
        } catch (IllegalArgumentException | DateTimeParseException ex) {
            throw new DurableEvidenceException("erasure set entry is not in the evidence format");
        }
        // The digest is over the evidence text: refuse an entry whose text the format would not
        // write back identically (a lowercase UUID, an Instant at microsecond precision).
        if (!entry.authUserId().toString().equals(authUserId)
                || !DurableErasureEvidence.erasedAt(entry.erasedAt()).equals(erasedAt)) {
            throw new DurableEvidenceException("erasure set entry is not in the evidence format");
        }
        return entry;
    }

    private static JsonNode read(byte[] raw) {
        try {
            return JSON.readTree(raw);
        } catch (IOException ex) {
            throw new DurableEvidenceException("unreadable evidence object");
        }
    }
}
