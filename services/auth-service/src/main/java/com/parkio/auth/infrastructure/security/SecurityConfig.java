package com.parkio.auth.infrastructure.security;

import com.parkio.auth.infrastructure.recovery.RecoveryReplayLaunch;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import com.parkio.auth.presentation.RefreshCookieProperties;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * Stateless JWT security. Authentication endpoints and actuator probes are
 * public; everything else (e.g. {@code /api/v1/auth/me}) requires a valid
 * bearer token. Sessions are disabled (token API); CSRF protection is route-specific for the
 * cookie transport, see {@link CookieTransportCsrf}.
 */
@Configuration
@EnableWebSecurity
// Not in the recovery-replay command context (PR #295 review B5): the command context has no web server.
@Profile("!" + RecoveryReplayLaunch.PROFILE)
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   RestAuthenticationEntryPoint entryPoint,
                                                   CsrfAccessDeniedHandler csrfAccessDeniedHandler,
                                                   RefreshCookieProperties refreshCookie) throws Exception {
        http
                // CSRF protection is route-specific: only the cookie-authenticated endpoints (refresh-token,
                // logout) from browser-shaped clients need the double-submit token; everything else carries a
                // bearer token or credentials in the body (CodeQL #7, CookieTransportCsrf).
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieTransportCsrf.repository(refreshCookie))
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                        .requireCsrfProtectionMatcher(CookieTransportCsrf.protectedRequests(refreshCookie.getName())))
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST,
                                "/api/v1/auth/register",
                                "/api/v1/auth/login",
                                "/api/v1/auth/verify-email",
                                "/api/v1/auth/resend-verification",
                                "/api/v1/auth/forgot-password",
                                "/api/v1/auth/reset-password",
                                "/api/v1/auth/refresh-token",
                                "/api/v1/auth/logout").permitAll()
                        .requestMatchers(HttpMethod.GET,
                                "/api/v1/auth/.well-known/jwks.json",
                                // Hands the CSRF token to the web client (cookie + body); it reveals nothing
                                // a cross-site page could use: CORS keeps the body from it.
                                "/api/v1/auth/csrf",
                                // Informational registration bootstrap — exact GET only.
                                // State-changing methods on this path remain authenticated.
                                "/api/v1/auth/registration-mode").permitAll()
                        // Internal service-to-service endpoints (e.g. the gateway's session-epoch
                        // check) carry no JWT; they are guarded by the X-Gateway-Auth shared
                        // secret in GatewayAuthFilter and are not routed publicly.
                        .requestMatchers("/internal/**").permitAll()
                        .requestMatchers("/actuator/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(entryPoint)
                        .accessDeniedHandler(csrfAccessDeniedHandler))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterBefore(new JwtAuthenticationFilter(jwtService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}
