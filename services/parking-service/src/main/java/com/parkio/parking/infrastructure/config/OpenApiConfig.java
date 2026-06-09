package com.parkio.parking.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI parkioOpenAPI() {
        final String bearer = "bearerAuth";
        final String gateway = "gatewayAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("Parkio Parking API")
                        .version("v1")
                        .description("Parking spot lifecycle, search and verification. Gateway path: `/api/v1/parking/**`."))
                .addSecurityItem(new SecurityRequirement().addList(bearer))
                .components(new Components()
                        .addSecuritySchemes(bearer, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("RS256 access token from auth-service"))
                        .addSecuritySchemes(gateway, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Gateway-Auth")
                                .description("Internal only — gateway stamps this on routed requests.")));
    }
}
