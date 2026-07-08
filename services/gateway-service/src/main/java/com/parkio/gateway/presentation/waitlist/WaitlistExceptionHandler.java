package com.parkio.gateway.presentation.waitlist;

import com.parkio.gateway.application.waitlist.WaitlistRateLimitExceededException;
import com.parkio.gateway.shared.ApiError;
import com.parkio.gateway.shared.GatewayHeaders;
import java.time.Clock;
import org.springframework.http.HttpStatus;
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
    public Mono<ApiError> validation(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.BAD_REQUEST);
        return Mono.just(error(exchange, "VALIDATION_ERROR", "Waitlist request is invalid."));
    }

    @ExceptionHandler(WaitlistRateLimitExceededException.class)
    public Mono<ApiError> rateLimit(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        return Mono.just(error(exchange, "RATE_LIMITED", "Too many waitlist submissions. Try again later."));
    }

    private ApiError error(ServerWebExchange exchange, String code, String message) {
        String correlationId = (String) exchange.getAttributes().get(GatewayHeaders.CORRELATION_ID_ATTRIBUTE);
        return new ApiError(code, message, correlationId, clock.instant());
    }
}
