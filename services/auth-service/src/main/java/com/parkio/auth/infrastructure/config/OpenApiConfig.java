package com.parkio.auth.infrastructure.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI metadata for machine-readable API contracts (ai-context/04).
 * Exposed at {@code /v3/api-docs} and {@code /swagger-ui.html} when
 * {@code parkio.openapi.enabled=true} (default locally; disable in production).
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI parkioOpenAPI() {
        final String bearer = "bearerAuth";
        final String gateway = "gatewayAuth";
        return new OpenAPI()
                .info(new Info()
                        .title("Parkio Auth API")
                        .version("v1")
                        .description("""
                                Authentication, RS256 access-token issuance and JWKS discovery.
                                Clients call these paths through the API gateway at \
                                `/api/v1/auth/**` (port 8080 locally)."""))
                .addSecurityItem(new SecurityRequirement().addList(bearer))
                .components(new Components()
                        .addSecuritySchemes(bearer, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("RS256 access token from POST /api/v1/auth/login"))
                        .addSecuritySchemes(gateway, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Gateway-Auth")
                                .description("Internal only — gateway stamps this on routed requests; "
                                        + "not for browser clients.")));
    }
}
