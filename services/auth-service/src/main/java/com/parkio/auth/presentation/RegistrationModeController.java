package com.parkio.auth.presentation;

import com.parkio.auth.infrastructure.config.RegistrationProperties;
import com.parkio.auth.presentation.dto.RegistrationModeResponse;
import java.time.Duration;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth/registration-mode")
public class RegistrationModeController {
    private final RegistrationProperties properties;

    public RegistrationModeController(RegistrationProperties properties) {
        this.properties = properties;
    }

    @GetMapping
    public ResponseEntity<RegistrationModeResponse> get() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(new RegistrationModeResponse(properties.getMode()));
    }
}
