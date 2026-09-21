package com.parkio.gateway.application.waitlist;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WaitlistInterestRepository {

    /** @return true when a new pending row was inserted */
    boolean insertPendingIfAbsent(WaitlistInterest interest);

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

    List<WaitlistExportRow> exportConfirmed(Instant createdFrom, Instant createdTo);

    WaitlistAdminCounts countByStatus();

    WaitlistAdminPage findAdminPage(
            WaitlistStatus status,
            Instant createdFrom,
            Instant createdTo,
            int page,
            int size);
}
