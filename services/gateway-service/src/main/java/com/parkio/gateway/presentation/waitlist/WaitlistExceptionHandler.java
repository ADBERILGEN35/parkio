package com.parkio.gateway.presentation.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistAdmissionsDisabledException;
import com.parkio.gateway.application.waitlist.WaitlistConsentTimestampException;
import com.parkio.gateway.application.waitlist.WaitlistEmailDeliveryException;
import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.application.waitlist.WaitlistTokenException;
import com.parkio.gateway.shared.ApiError;
import com.parkio.gateway.shared.GatewayHeaders;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@RestControllerAdvice(assignableTypes = WaitlistController.class)
public class WaitlistExceptionHandler {

    private final Clock clock;

    public WaitlistExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(WebExchangeBindException.class)
    public Mono<ApiError> validation(WebExchangeBindException ex, ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        boolean consentField = ex.getFieldErrors().stream()
                .map(FieldError::getField)
                .anyMatch("consentTimestamp"::equals);
        boolean emailField = ex.getFieldErrors().stream()
                .map(FieldError::getField)
                .anyMatch("email"::equals);
        if (consentField && !emailField) {
            return Mono.just(error(
                    exchange,
                    "WAITLIST_CONSENT_TIMESTAMP_INVALID",
                    "Waitlist consent timestamp is missing or outside the accepted time window."));
        }
        if (emailField) {
            return Mono.just(error(exchange, "VALIDATION_ERROR", "Waitlist request is invalid."));
        }
        return Mono.just(error(exchange, "VALIDATION_ERROR", "Waitlist request is invalid."));
    }

    @ExceptionHandler(WaitlistConsentTimestampException.class)
    public Mono<ApiError> consentTimestamp(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        return Mono.just(error(
                exchange,
                "WAITLIST_CONSENT_TIMESTAMP_INVALID",
                "Waitlist consent timestamp is missing or outside the accepted time window."));
    }

    @ExceptionHandler(WaitlistRateLimitExceededException.class)
    public Mono<ApiError> rateLimit(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return Mono.just(error(exchange, "RATE_LIMITED", "Too many waitlist submissions. Try again later."));
    }

    @ExceptionHandler(WaitlistAdmissionsDisabledException.class)
    public Mono<ApiError> admissionsDisabled(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(error(
                exchange,
                "WAITLIST_ADMISSIONS_DISABLED",
                "Waitlist registration is temporarily unavailable. Please try again later."));
    }

    @ExceptionHandler(WaitlistTokenException.class)
    public Mono<ApiError> token(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        return Mono.just(error(exchange, "WAITLIST_TOKEN_INVALID", "Waitlist token is invalid or expired."));
    }

    @ExceptionHandler(WaitlistEmailDeliveryException.class)
    public Mono<ApiError> delivery(ServerWebExchange exchange) {
        // Durable pending row remains; client may retry. Do not claim confirmed delivery.
        exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        return Mono.just(error(
                exchange,
                "WAITLIST_EMAIL_DELIVERY_FAILED",
                "Waitlist signup was saved but confirmation email could not be sent. Please try again."));
    }

    private ApiError error(ServerWebExchange exchange, String code, String message) {
        String correlationId = (String) exchange.getAttributes().get(GatewayHeaders.CORRELATION_ID_ATTRIBUTE);
        return new ApiError(code, message, correlationId, clock.instant());
    }
}
