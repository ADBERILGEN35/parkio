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
     * would pick Jackson 3 whenever it is on the classpath. Only the JSON converter is replaced, the way
     * Spring Boot configures its own builder; the other default converters stay as they are.
     */
    @Bean
    @SuppressWarnings("removal")
    RestClient.Builder waitlistRestClientBuilder(ObjectMapper objectMapper) {
        return RestClient.builder()
                .configureMessageConverters(converters ->
                        converters.withJsonConverter(new MappingJackson2HttpMessageConverter(objectMapper)));
    }
}
