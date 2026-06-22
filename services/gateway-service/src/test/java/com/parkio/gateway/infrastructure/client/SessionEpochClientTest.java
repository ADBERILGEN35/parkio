package com.parkio.gateway.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Maps auth-service's internal session-epoch responses: 2xx → the current epoch; any
 * other status (incl. 404 unknown user) → fail-closed unavailability so the gateway
 * never lets a possibly-revoked token through.
 */
class SessionEpochClientTest {

    private SessionEpochClient clientReturning(ClientResponse response) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://auth-service")
                .exchangeFunction(stub(response))
                .build();
        // These tests assert status→epoch mapping, not timeout behaviour. Use a
        // generous request timeout so the reactor `.timeout()` operator's MonoDelay
        // (scheduled on Schedulers.parallel()) cannot misfire and fail the mapping
        // assertion when the parallel scheduler is starved under heavy parallel test
        // execution. The production default (2s) is exercised by integration paths.
        SessionEpochProperties properties = new SessionEpochProperties();
        properties.setRequestTimeout(Duration.ofSeconds(30));
        return new SessionEpochClient(webClient, properties);
    }

    @Test
    void mapsOkBodyToCurrentEpoch() {
        ClientResponse ok = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"userId\":\"u1\",\"sessionEpoch\":5}")
                .build();

        Long epoch = clientReturning(ok).fetchCurrentEpoch("u1").block();

        assertThat(epoch).isEqualTo(5L);
    }

    @Test
    void mapsNotFoundToUnavailable() {
        ClientResponse notFound = ClientResponse.create(HttpStatus.NOT_FOUND).build();

        assertThatThrownBy(() -> clientReturning(notFound).fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    @Test
    void mapsServerErrorToUnavailable() {
        ClientResponse error = ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build();

        assertThatThrownBy(() -> clientReturning(error).fetchCurrentEpoch("u1").block())
                .isInstanceOf(SessionEpochUnavailableException.class);
    }

    private static ExchangeFunction stub(ClientResponse response) {
        return request -> Mono.just(response);
    }
}
