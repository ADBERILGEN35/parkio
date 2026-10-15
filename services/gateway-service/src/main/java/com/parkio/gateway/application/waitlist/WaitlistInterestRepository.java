package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;

public interface WaitlistInterestRepository {

    /** @return true when a new pending row was inserted */
    boolean insertPendingIfAbsent(WaitlistInterest interest);

    Optional<WaitlistInterest> findById(UUID id);

    Optional<WaitlistInterest> findByEmailHash(String emailHash);

    /**
     * Rewrites identity digests for a row matched under a previous email secret (CL-N08).
     * Returns true when the row was updated from {@code fromEmailHash} to {@code toEmailHash}.
     */
    boolean rehashEmailIdentity(String fromEmailHash, String toEmailHash, String toIpHash, String toUserAgentHash);

    Optional<WaitlistInterest> findByVerificationTokenHash(String tokenHash);

    Optional<WaitlistInterest> findByWithdrawTokenHash(String tokenHash);

    boolean confirmByTokenHash(String tokenHash, Instant now);

    boolean withdrawByTokenHash(String tokenHash, Instant now);

    /**
     * Atomically claim a pending resend by CAS on {@code verification_claim_version}
     * and the resend cooldown. On win: rotates tokens, increments the claim version,
     * records {@code verification_claimed_at}, leaves sent_at/resend_count unchanged so
     * failed delivery stays retryable. Loser returns empty and must not send.
     *
     * @param claimedAt timestamp written on a winning claim
     * @param cooldownElapsedBefore claim succeeds only when both {@code verification_sent_at}
     *     and {@code verification_claimed_at} are null or {@code <=} this instant
     * @return the new claim version when this caller won; empty when the CAS lost
     */
    OptionalLong claimPendingVerification(
            String emailHash,
            long expectedClaimVersion,
            String verificationTokenHash,
            String withdrawTokenHash,
            Instant expiresAt,
            Instant claimedAt,
            Instant cooldownElapsedBefore);

    /**
     * Records a successful outbound confirmation attempt bound to the winning claim.
     * A stale claim (superseded by a newer claim) updates zero rows and returns false.
     * {@code resend_count} is advanced with {@code GREATEST} so overlapping completions
     * cannot hold the counter back.
     */
    boolean markVerificationSent(String emailHash, long claimVersion, Instant sentAt, int resendCount);

    /** Confirmed subscriptions whose confirmation time lies in {@code [confirmedFrom, confirmedTo)}. */
    long countConfirmedForExport(Instant confirmedFrom, Instant confirmedTo);

    /**
     * The next page of confirmed subscriptions in {@code [confirmedFrom, confirmedTo)}, ordered by
     * confirmation time then id, strictly after {@code after} ({@code null} for the first page).
     */
    List<WaitlistExportRow> exportConfirmedPage(Instant confirmedFrom, Instant confirmedTo,
                                                WaitlistExportCursor after, int limit);

    WaitlistAdminCounts countByStatus();

    long countConfirmed();

    /** Confirmed subscribers with {@code confirmed_at >= sinceInclusive}. */
    long countConfirmedSince(Instant sinceInclusive);

    WaitlistAdminPage findAdminPage(
            WaitlistStatus status,
            Instant createdFrom,
            Instant createdTo,
            int page,
            int size);
}
