package com.parkio.media.infrastructure.config;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Stops startup when the object-storage credentials are missing, or when they are MinIO's
 * well-known default (minioadmin) outside the local {@code dev} profile (CL-F38d). Values are
 * never included in the message. The MinIO client beans depend on it, so it runs before the
 * client builder rejects empty credentials with a message that names no setting.
 */
@Component(MediaStorageCredentialsGuard.BEAN_NAME)
public class MediaStorageCredentialsGuard implements InitializingBean {

    static final String BEAN_NAME = "mediaStorageCredentialsGuard";
    static final String MINIO_DEFAULT = "minioadmin";

    private final MediaProperties properties;
    private final Environment environment;

    public MediaStorageCredentialsGuard(MediaProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    @Override
    public void afterPropertiesSet() {
        MediaProperties.Storage storage = properties.getStorage();
        if (!StringUtils.hasText(storage.getAccessKey()) || !StringUtils.hasText(storage.getSecretKey())) {
            throw new IllegalStateException("parkio.media.storage.access-key and secret-key "
                    + "(PARKIO_MEDIA_STORAGE_ACCESS_KEY / PARKIO_MEDIA_STORAGE_SECRET_KEY) must be configured");
        }
        boolean defaults = MINIO_DEFAULT.equalsIgnoreCase(storage.getAccessKey().trim())
                || MINIO_DEFAULT.equalsIgnoreCase(storage.getSecretKey().trim());
        if (defaults && !environment.acceptsProfiles(Profiles.of("dev"))) {
            throw new IllegalStateException("the MinIO default credentials (minioadmin) are only allowed "
                    + "with the dev profile; set PARKIO_MEDIA_STORAGE_ACCESS_KEY / PARKIO_MEDIA_STORAGE_SECRET_KEY");
        }
    }
}
