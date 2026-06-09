package com.parkio.gateway.infrastructure.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Maps user-service's internal status responses to {@link UserStatusLookup} outcomes:
 * 2xx → found, 404 → not-found, any other status → fail-closed unavailability.
 */
class UserStatusClientTest {

    private UserStatusClient clientReturning(ClientResponse response) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://user-service")
                .exchangeFunction(stub(response))
                .build();
        return new UserStatusClient(webClient, new UserStatusProperties());
    }

    @Test
    void mapsOkBodyToFoundStatus() {
        ClientResponse ok = ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{\"userId\":\"u1\",\"status\":\"ACTIVE\"}")
                .build();

        UserStatusLookup lookup = clientReturning(ok).fetchStatus("u1").block();

        assertThat(lookup).isNotNull();
        assertThat(lookup.found()).isTrue();
        assertThat(lookup.status()).isEqualTo("ACTIVE");
    }

    @Test
    void mapsNotFoundToNotFoundLookup() {
        ClientResponse notFound = ClientResponse.create(HttpStatus.NOT_FOUND).build();

        UserStatusLookup lookup = clientReturning(notFound).fetchStatus("u1").block();

        assertThat(lookup).isNotNull();
        assertThat(lookup.found()).isFalse();
    }

    @Test
    void mapsServerErrorToUnavailable() {
        ClientResponse error = ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build();

        assertThatThrownBy(() -> clientReturning(error).fetchStatus("u1").block())
                .isInstanceOf(UserStatusUnavailableException.class);
    }

    private static ExchangeFunction stub(ClientResponse response) {
        return request -> Mono.just(response);
    }
}
