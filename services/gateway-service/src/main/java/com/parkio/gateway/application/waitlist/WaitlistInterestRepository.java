package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WaitlistInterestRepository {

    /** @return true when a new pending row was inserted */
    boolean insertPendingIfAbsent(WaitlistInterest interest);

    Optional<WaitlistInterest> findById(UUID id);

    Optional<WaitlistInterest> findByEmailHash(String emailHash);

    Optional<WaitlistInterest> findByVerificationTokenHash(String tokenHash);

    Optional<WaitlistInterest> findByWithdrawTokenHash(String tokenHash);

    boolean confirmByTokenHash(String tokenHash, Instant now);

    boolean withdrawByTokenHash(String tokenHash, Instant now);

    boolean refreshPendingVerification(
            String emailHash,
            String verificationTokenHash,
            String withdrawTokenHash,
            Instant expiresAt,
            Instant sentAt,
            int resendCount);

    /** Records a successful outbound confirmation attempt (after delivery succeeds). */
    void markVerificationSent(String emailHash, Instant sentAt, int resendCount);

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
