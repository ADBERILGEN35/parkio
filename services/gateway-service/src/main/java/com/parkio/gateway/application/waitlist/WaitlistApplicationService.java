package com.parkio.gateway.application.waitlist;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Service
public class WaitlistApplicationService {

    private final WaitlistInterestRepository repository;
    private final WaitlistHasher hasher;
    private final WaitlistRateLimiter rateLimiter;
    private final Clock clock;

    public WaitlistApplicationService(
            WaitlistInterestRepository repository,
            WaitlistHasher hasher,
            WaitlistRateLimiter rateLimiter,
            Clock clock) {
        this.repository = repository;
        this.hasher = hasher;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
    }

    public Mono<Void> submit(SubmitWaitlistCommand command) {
        String email = normalizeEmail(command.email());
        String city = normalizeOptional(command.city());
        String role = normalizeOptional(command.role());
        String userAgent = normalizeOptional(command.userAgent());
        String emailHash = hasher.hash(email);
        String ipHash = hasher.hash(command.clientIp() == null ? "unknown" : command.clientIp());
        String userAgentHash = userAgent == null ? null : hasher.hash(userAgent);
        Instant now = clock.instant();
        WaitlistInterest interest = new WaitlistInterest(
                UUID.randomUUID(),
                email,
                emailHash,
                command.consentTimestamp(),
                city,
                role,
                command.source(),
                ipHash,
                userAgentHash,
                now);
        return rateLimiter.check(ipHash, emailHash)
                .then(Mono.fromRunnable(() -> repository.insertIfAbsent(interest))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then();
    }

    public Mono<List<WaitlistExportRow>> export(Instant createdFrom, Instant createdTo) {
        return Mono.fromCallable(() -> repository.export(createdFrom, createdTo))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String normalizeEmail(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
