package com.parkio.gateway.infrastructure.waitlist;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.web.client.RestClient;

@Configuration
public class WaitlistRestClientConfig {

    /**
     * JSON goes through the application's Jackson 2 mapper, like every other HTTP path of the gateway
     * (spring.http.codecs.preferred-json-mapper=jackson2). Spring Framework 7's plain RestClient.builder()
     * would pick Jackson 3 whenever it is on the classpath. As in Spring Boot's own builder, the defaults are
     * registered first (without registerDefaults() the client would lose the String, byte[] and Resource
     * converters) and only the JSON converter is replaced.
     */
    @Bean
    @SuppressWarnings("removal")
    RestClient.Builder waitlistRestClientBuilder(ObjectMapper objectMapper) {
        return RestClient.builder()
                .configureMessageConverters(converters -> converters.registerDefaults()
                        .withJsonConverter(new MappingJackson2HttpMessageConverter(objectMapper)));
    }
}
