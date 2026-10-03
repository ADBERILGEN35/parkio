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
import org.springframework.transaction.support.TransactionOperations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class WaitlistApplicationService {

    private static final Logger log = LoggerFactory.getLogger(WaitlistApplicationService.class);
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    /** Rows per export query; with the keyset this bounds what one export holds in memory. */
    static final int EXPORT_PAGE_SIZE = 1_000;

    private final WaitlistInterestRepository repository;
    private final WaitlistHasher hasher;
    private final WaitlistRateLimiter rateLimiter;
    private final WaitlistEmailSender emailSender;
    private final WaitlistProperties properties;
    private final WaitlistOpsNotifier opsNotifier;
    private final TransactionOperations transactions;
    private final Clock clock;

    public WaitlistApplicationService(
            WaitlistInterestRepository repository,
            WaitlistHasher hasher,
            WaitlistRateLimiter rateLimiter,
            WaitlistEmailSender emailSender,
            WaitlistProperties properties,
            WaitlistOpsNotifier opsNotifier,
            TransactionOperations transactions,
            Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
        this.emailSender = emailSender;
        this.properties = properties;
        this.opsNotifier = opsNotifier;
        this.transactions = transactions;
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
        String fullName;
        try {
            fullName = WaitlistFullName.requireOrOptional(command.fullName(), properties.isFullNameRequired());
        } catch (WaitlistFullNameException ex) {
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
                fullName,
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
                    // The ops notification row commits atomically with the PENDING -> CONFIRMED
                    // transition, so rolled-back or idempotent repeat confirms emit nothing.
                    Boolean transitioned = transactions.execute(tx -> {
                        if (!repository.confirmByTokenHash(tokenHash, now)) {
                            return false;
                        }
                        repository.findByVerificationTokenHash(tokenHash)
                                .ifPresent(row -> opsNotifier.subscriptionConfirmed(row.id(), now));
                        return true;
                    });
                    if (Boolean.TRUE.equals(transitioned)) {
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
                            resendPendingWithoutRevealingIt(emailHash, email);
                            return true;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    /**
     * Resend answers 202 whether or not a PENDING row exists (CL-F14.3). Only an
     * existing PENDING row reaches the provider, so a delivery failure must not turn
     * into a 503 here: that would tell the caller the address is subscribed. The
     * failure stays visible to operators through the WARN log in
     * {@link #deliverConfirmation}, and the row stays unsent, so the next resend or
     * submit retries the delivery straight away.
     */
    private void resendPendingWithoutRevealingIt(String emailHash, String email) {
        try {
            maybeResendPending(emailHash, email);
        } catch (WaitlistEmailDeliveryException ex) {
            log.warn("Waitlist resend answered 202 after a failed confirmation delivery; emailHash={}", emailHash);
        }
    }

    /**
     * Confirmed subscriptions whose confirmation time lies in {@code [confirmedFrom, confirmedTo)},
     * at most {@code parkio.waitlist.export.max-rows} of them. The match is counted first, so the
     * caller can report truncation before streaming; the count and the pages are separate reads,
     * so a row confirmed while the export runs can be streamed although it was not counted. The
     * rows come one keyset page at a time without a database cursor. Memory does not grow with
     * the matching volume, but it is not one page either: the HTTP layer prefetches a bounded
     * number of buffers (Reactor Netty asks for up to 128 pages), and at the default cap of 50,000
     * rows the whole export is 50 pages.
     */
    public Mono<WaitlistExport> export(Instant confirmedFrom, Instant confirmedTo) {
        int limit = properties.getExport().getMaxRows();
        return Mono.fromCallable(() -> repository.countConfirmedForExport(confirmedFrom, confirmedTo))
                .subscribeOn(Schedulers.boundedElastic())
                .map(matching -> new WaitlistExport(matching, limit, exportPages(confirmedFrom, confirmedTo, limit)));
    }

    private Flux<List<WaitlistExportRow>> exportPages(Instant confirmedFrom, Instant confirmedTo, int limit) {
        return Flux.<List<WaitlistExportRow>, ExportProgress>generate(
                        () -> new ExportProgress(null, 0),
                        (progress, sink) -> {
                            int wanted = Math.min(EXPORT_PAGE_SIZE, limit - progress.emitted());
                            if (wanted <= 0) {
                                sink.complete();
                                return progress;
                            }
                            List<WaitlistExportRow> page =
                                    repository.exportConfirmedPage(confirmedFrom, confirmedTo, progress.after(), wanted);
                            if (page.isEmpty()) {
                                sink.complete();
                                return progress;
                            }
                            sink.next(page);
                            WaitlistExportRow last = page.get(page.size() - 1);
                            return new ExportProgress(new WaitlistExportCursor(last.confirmedAt(), last.id()),
                                    progress.emitted() + page.size());
                        })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private record ExportProgress(WaitlistExportCursor after, int emitted) {
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
