package com.parkio.parking.infrastructure.client;

import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

/**
 * A {@link RestClient.Builder} that reads and writes JSON with Jackson 2, like the builder Spring Boot injects
 * in production ({@code spring.http.converters.preferred-json-mapper=jackson2}). Framework 7's plain
 * {@code RestClient.builder()} would pick Jackson 3, which cannot produce the Jackson 2 {@code JsonNode} trees
 * and annotated DTOs the municipal feed clients read.
 */
public final class Jackson2RestClientBuilder {

    private Jackson2RestClientBuilder() {
    }

    @SuppressWarnings("removal")
    public static RestClient.Builder builder() {
        return RestClient.builder()
                .configureMessageConverters(converters -> converters.withJsonConverter(new MappingJackson2HttpMessageConverter()));
    }
}
