package com.parkio.gateway.infrastructure.waitlist;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WaitlistRestClientConfig {

    @Bean
    RestClient.Builder waitlistRestClientBuilder() {
        return RestClient.builder();
    }
}
