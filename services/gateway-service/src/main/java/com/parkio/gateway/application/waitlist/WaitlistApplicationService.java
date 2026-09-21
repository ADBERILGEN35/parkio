package com.parkio.gateway.application.waitlist;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class WaitlistApplicationService {

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
        String email = normalizeEmail(command.email());
        String locale = normalizeLocale(command.locale());
        String city = normalizeOptional(command.city());
        String role = normalizeOptional(command.role());
        String userAgent = normalizeOptional(command.userAgent());
        String emailHash = hasher.hash(email);
        String ipHash = hasher.hash(command.clientIp() == null ? "unknown" : command.clientIp());
        String userAgentHash = userAgent == null ? null : hasher.hash(userAgent);
        Instant now = clock.instant();
        String verificationToken = newToken();
        String withdrawToken = newToken();
        WaitlistInterest interest = new WaitlistInterest(
                UUID.randomUUID(),
                email,
                emailHash,
                command.consentTimestamp(),
                city,
                role,
                command.source(),
                locale,
                WaitlistStatus.PENDING,
                hasher.hash(verificationToken),
                hasher.hash(withdrawToken),
                now.plus(properties.getTokenTtl()),
                now,
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
                                emailSender.sendConfirmation(email, verificationToken, withdrawToken, locale);
                            }
                            return true;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    public Mono<Void> confirm(String rawToken) {
        return Mono.fromCallable(() -> {
                    String tokenHash = hasher.hash(requireToken(rawToken));
                    Instant now = clock.instant();
                    if (repository.confirmByTokenHash(tokenHash, now)) {
                        return true;
                    }
                    Optional<WaitlistInterest> byVerify = repository.findByVerificationTokenHash(tokenHash);
                    return byVerify.isPresent() && byVerify.get().status() == WaitlistStatus.CONFIRMED;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ok -> Boolean.TRUE.equals(ok)
                        ? Mono.empty()
                        : Mono.error(new WaitlistTokenException("WAITLIST_TOKEN_INVALID")));
    }

    public Mono<Void> withdraw(String rawToken) {
        return Mono.fromCallable(() -> {
                    String tokenHash = hasher.hash(requireToken(rawToken));
                    Instant now = clock.instant();
                    Optional<WaitlistInterest> before = repository.findByWithdrawTokenHash(tokenHash);
                    boolean withdrawn = repository.withdrawByTokenHash(tokenHash, now);
                    if (withdrawn && before.isPresent()) {
                        emailSender.sendWithdrawalNotice(before.get().email(), before.get().locale());
                    }
                    return withdrawn;
                })
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(ok -> Boolean.TRUE.equals(ok)
                        ? Mono.empty()
                        : Mono.error(new WaitlistTokenException("WAITLIST_TOKEN_INVALID")));
    }

    public Mono<Void> resend(String emailRaw, String clientIp) {
        String email = normalizeEmail(emailRaw);
        String emailHash = hasher.hash(email);
        String ipHash = hasher.hash(clientIp == null ? "unknown" : clientIp);
        return rateLimiter.check(ipHash, emailHash)
                .then(Mono.fromCallable(() -> {
                            Optional<WaitlistInterest> existing = repository.findByEmailHash(emailHash);
                            if (existing.isEmpty() || existing.get().status() != WaitlistStatus.PENDING) {
                                return true;
                            }
                            WaitlistInterest row = existing.get();
                            Instant now = clock.instant();
                            if (row.resendCount() >= properties.getMaxResends()) {
                                return true;
                            }
                            if (row.verificationSentAt() != null
                                    && row.verificationSentAt()
                                            .plus(properties.getResendCooldown())
                                            .isAfter(now)) {
                                return true;
                            }
                            String verificationToken = newToken();
                            boolean refreshed = repository.refreshPendingVerification(
                                    emailHash,
                                    hasher.hash(verificationToken),
                                    now.plus(properties.getTokenTtl()),
                                    now,
                                    row.resendCount() + 1);
                            if (refreshed) {
                                emailSender.sendConfirmation(row.email(), verificationToken, null, row.locale());
                            }
                            return true;
                        })
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    public Mono<List<WaitlistExportRow>> export(Instant createdFrom, Instant createdTo) {
        return Mono.fromCallable(() -> repository.exportConfirmed(createdFrom, createdTo))
                .subscribeOn(Schedulers.boundedElastic());
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
