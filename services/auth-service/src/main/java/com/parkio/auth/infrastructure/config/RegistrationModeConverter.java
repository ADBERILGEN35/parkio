package com.parkio.auth.infrastructure.config;

import com.parkio.auth.domain.RegistrationMode;
import org.springframework.boot.context.properties.ConfigurationPropertiesBinding;
import org.springframework.core.convert.converter.Converter;
import org.springframework.stereotype.Component;

/** Binds {@code parkio.registration.mode} with fail-fast validation for unknown values. */
@Component
@ConfigurationPropertiesBinding
public class RegistrationModeConverter implements Converter<String, RegistrationMode> {

    @Override
    public RegistrationMode convert(String source) {
        return RegistrationMode.parse(source);
    }
}
