package com.parkio.parking.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.parkio.parking.infrastructure.config.MunicipalSourceProperties;
import java.net.SocketTimeoutException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * Read-only ANPARK facility list client. Base URL and path come from configuration only
 * (no user-controlled URLs). List-only; does not call payment/debt endpoints.
 */
@Component
public class AnparkParkingClient {
    private final RestClient client;
    private final MunicipalSourceProperties.Anpark config;

    public AnparkParkingClient(RestClient.Builder builder, MunicipalSourceProperties properties) {
        this.config = properties.getAnpark();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(config.getConnectTimeout());
        factory.setReadTimeout(config.getReadTimeout());
        this.client = builder.baseUrl(config.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(HttpHeaders.USER_AGENT, config.getUserAgent())
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public JsonNode fetch() {
        int attempt = 0;
        while (true) {
            try {
                return client.get().uri(config.getPath()).retrieve().body(JsonNode.class);
            } catch (HttpClientErrorException ex) {
                throw ex;
            } catch (RestClientException ex) {
                if (attempt++ >= config.getMaxRetries() || !transientFailure(ex)) {
                    throw ex;
                }
                try {
                    Thread.sleep(Math.min(1000L, 100L * attempt));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw ex;
                }
            }
        }
    }

    private static boolean transientFailure(RestClientException ex) {
        if (ex instanceof RestClientResponseException response) {
            return response.getStatusCode().is5xxServerError();
        }
        Throwable cause = ex;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException) {
                return true;
            }
            cause = cause.getCause();
        }
        return true;
    }
}
