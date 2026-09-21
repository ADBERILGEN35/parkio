package com.parkio.gateway.application.waitlist;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class WaitlistApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistApplicationService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final WaitlistInterestRepository repository;
    private final WaitlistHasher hasher;
    private final WaitlistRateLimiter rateLimiter;
    private final WaitlistEmailSender emailSender;
    private final WaitlistProperties properties;
    private final Clock clock;

    public WaitlistApplicationService(
            WaitlistInterestRepository repository,
            WaitlistHasher hasher,
            WaitlistRateLimiter rateLimiter,
            WaitlistEmailSender emailSender,
            WaitlistProperties properties,
            Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
        this.emailSender = emailSender;
        this.properties = properties;
        this.clock = clock;
    }

    public Mono<Void> submit(SubmitWaitlistCommand command) {
        if (!properties.isAdmissionsEnabled()) {
            return Mono.error(new WaitlistAdmissionsDisabledException("WAITLIST_ADMISSIONS_DISABLED"));
        }
        Instant now = clock.instant();
        Instant clientConsentAt;
        try {
            clientConsentAt = requireClientConsentTimestamp(command.consentTimestamp(), now);
        } catch (WaitlistConsentTimestampException ex) {
            return Mono.error(ex);
        }
        String email = normalizeEmail(command.email());
        String locale = normalizeLocale(command.locale());
        String city = normalizeOptional(command.city());
        String role = normalizeOptional(command.role());
        String userAgent = normalizeOptional(command.userAgent());
        String emailHash = hasher.hash(email);
        String ipHash = hasher.hash(command.clientIp() == null ? "unknown" : command.clientIp());
        String userAgentHash = userAgent == null ? null : hasher.hash(userAgent);
        String verificationToken = newToken();
        String withdrawToken = newToken();
        // verification_sent_at stays null until outbound delivery succeeds so failed
        // first sends are immediately retryable (not blocked by resend cooldown).
        // consent_timestamp = server receipt (authoritative). clientConsentTimestamp =
        // skew-validated browser assertion preserved separately.
        WaitlistInterest interest = new WaitlistInterest(
                UUID.randomUUID(),
                email,
                emailHash,
                now,
                clientConsentAt,
                city,
                role,
                command.source(),
                locale,
                WaitlistStatus.PENDING,
                hasher.hash(verificationToken),
                hasher.hash(withdrawToken),
                now.plus(properties.getTokenTtl()),
                null,
                0,
                null,
                null,
                ipHash,
                userAgentHash,
                now);
        return rateLimiter.check(ipHash, emailHash)
                .then(Mono.fromCallable(() -> {
                            boolean inserted = repository.insertPendingIfAbsent(interest);
                            if (inserted) {
                                deliverConfirmation(email, verificationToken, withdrawToken, locale, emailHash, 0);
                                return true;
                            }
                            maybeResendPending(emailHash, email);
                            return true;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /**
     * Validates client-asserted consent event time against gateway UTC with a
     * documented bounded skew window. Does not silently rewrite the client value.
     */
    Instant requireClientConsentTimestamp(Instant clientConsentAt, Instant now) {
        if (clientConsentAt == null) {
            throw new WaitlistConsentTimestampException("WAITLIST_CONSENT_TIMESTAMP_INVALID");
        }
        Instant earliest = now.minus(properties.getConsentMaxPastAge());
        Instant latest = now.plus(properties.getConsentMaxFutureSkew());
        if (clientConsentAt.isBefore(earliest) || clientConsentAt.isAfter(latest)) {
            throw new WaitlistConsentTimestampException("WAITLIST_CONSENT_TIMESTAMP_INVALID");
        }
        return clientConsentAt;
    }

    public Mono<Void> confirm(String rawToken) {
        // Confirmation remains available during containment so already-issued tokens
        // can complete double opt-in. No new outbound email is sent here.
        return Mono.fromCallable(() -> {
                    String tokenHash = hasher.hash(requireToken(rawToken));
                    Instant now = clock.instant();
                    if (repository.confirmByTokenHash(tokenHash, now)) {
                        return true;
                    }
                    Optional<WaitlistInterest> byVerify = repository.findByVerificationTokenHash(tokenHash);
                    if (byVerify.isPresent() && byVerify.get().status() == WaitlistStatus.CONFIRMED) {
                        return true;
                    }
                    return false;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ok -> Boolean.TRUE.equals(ok)
                        ? Mono.empty()
                        : Mono.error(new WaitlistTokenException("WAITLIST_TOKEN_INVALID")));
    }

    public Mono<Void> withdraw(String rawToken) {
        // Withdrawal remains available during admissions containment so existing
        // subscribers can leave. Withdrawal notice email may still be attempted;
        // provider failures are logged without revealing subscriber PII and do not
        // roll back the withdrawal.
        return Mono.fromCallable(() -> {
                    String tokenHash = hasher.hash(requireToken(rawToken));
                    Instant now = clock.instant();
                    Optional<WaitlistInterest> before = repository.findByWithdrawTokenHash(tokenHash);
                    boolean withdrawn = repository.withdrawByTokenHash(tokenHash, now);
                    if (withdrawn && before.isPresent()) {
                        try {
                            emailSender.sendWithdrawalNotice(before.get().email(), before.get().locale());
                        } catch (RuntimeException ex) {
                            log.warn(
                                    "Waitlist withdrawal notice delivery failed; emailHash={}",
                                    before.get().emailHash());
                        }
                    }
                    return withdrawn;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ok -> Boolean.TRUE.equals(ok)
                        ? Mono.empty()
                        : Mono.error(new WaitlistTokenException("WAITLIST_TOKEN_INVALID")));
    }

    public Mono<Void> resend(String emailRaw, String clientIp) {
        if (!properties.isAdmissionsEnabled()) {
            return Mono.error(new WaitlistAdmissionsDisabledException("WAITLIST_ADMISSIONS_DISABLED"));
        }
        String email = normalizeEmail(emailRaw);
        String emailHash = hasher.hash(email);
        String ipHash = hasher.hash(clientIp == null ? "unknown" : clientIp);
        return rateLimiter.check(ipHash, emailHash)
                .then(Mono.fromCallable(() -> {
                            maybeResendPending(emailHash, email);
                            return true;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    public Mono<List<WaitlistExportRow>> export(Instant createdFrom, Instant createdTo) {
        return Mono.fromCallable(() -> repository.exportConfirmed(createdFrom, createdTo))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<WaitlistAdminCounts> adminCounts() {
        return Mono.fromCallable(repository::countByStatus)
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<WaitlistAdminPage> adminList(
            WaitlistStatus status,
            Instant createdFrom,
            Instant createdTo,
            int page,
            int size) {
        return Mono.fromCallable(() -> repository.findAdminPage(status, createdFrom, createdTo, page, size))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private void maybeResendPending(String emailHash, String email) {
        Optional<WaitlistInterest> existing = repository.findByEmailHash(emailHash);
        if (existing.isEmpty() || existing.get().status() != WaitlistStatus.PENDING) {
            return;
        }
        WaitlistInterest row = existing.get();
        Instant now = clock.instant();
        if (row.resendCount() >= properties.getMaxResends()) {
            return;
        }
        if (row.verificationSentAt() != null
                && row.verificationSentAt().plus(properties.getResendCooldown()).isAfter(now)) {
            return;
        }
        String verificationToken = newToken();
        String withdrawToken = newToken();
        int nextResendCount = row.resendCount() + (row.verificationSentAt() == null ? 0 : 1);
        // Rotate tokens first; mark sent only after delivery so failures remain retryable.
        boolean refreshed = repository.refreshPendingVerification(
                emailHash,
                hasher.hash(verificationToken),
                hasher.hash(withdrawToken),
                now.plus(properties.getTokenTtl()),
                row.verificationSentAt(),
                row.resendCount());
        if (refreshed) {
            deliverConfirmation(email, verificationToken, withdrawToken, row.locale(), emailHash, nextResendCount);
        }
    }

    private void deliverConfirmation(
            String email,
            String verificationToken,
            String withdrawToken,
            String locale,
            String emailHash,
            int resendCountAfterSuccess) {
        try {
            emailSender.sendConfirmation(email, verificationToken, withdrawToken, locale);
            repository.markVerificationSent(emailHash, clock.instant(), resendCountAfterSuccess);
        } catch (RuntimeException ex) {
            log.warn("Waitlist confirmation delivery failed after durable write; emailHash={}", emailHash);
            throw new WaitlistEmailDeliveryException("WAITLIST_EMAIL_DELIVERY_FAILED", ex);
        }
    }

    private static String requireToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank() || rawToken.length() > 256) {
            throw new WaitlistTokenException("WAITLIST_TOKEN_INVALID");
        }
        return rawToken.trim();
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "tr";
        }
        String normalized = locale.trim().toLowerCase(Locale.ROOT);
        return "en".equals(normalized) ? "en" : "tr";
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
