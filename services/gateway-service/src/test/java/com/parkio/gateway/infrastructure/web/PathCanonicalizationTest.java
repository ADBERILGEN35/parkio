package com.parkio.gateway.infrastructure.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathCanonicalizationTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/analytics/users/../overview",
            "/api/v1/parking/../../../internal/admin",
            "/api/v1/parking/%2e%2e/%2e%2e/internal",
            "/api/v1/parking/%252e%252e/secret",
            "/api/v1/parking/foo//bar",
            "/api/v1/parking/foo\\bar",
            "/api/v1/parking/..%2f..%2fetc/passwd"
    })
    void rejectsUnsafePaths(String path) {
        assertThat(PathCanonicalization.isUnsafeRawPath(path)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/parking/spots/nearby",
            "/api/v1/auth/login",
            "/api/v1/users/me",
            "/api/v1/analytics/users/overview",
            "/api/v1/media/upload"
    })
    void allowsNormalPaths(String path) {
        assertThat(PathCanonicalization.isUnsafeRawPath(path)).isFalse();
    }
}