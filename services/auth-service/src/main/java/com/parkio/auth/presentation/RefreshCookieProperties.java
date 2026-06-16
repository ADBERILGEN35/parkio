package com.parkio.auth.presentation;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** HTTP transport settings for the refresh-token cookie. */
@Component
@ConfigurationProperties(prefix = "parkio.security.refresh-cookie")
public class RefreshCookieProperties {

    private String name = "parkio_refresh";
    private String path = "/api/v1/auth/refresh-token";
    private String logoutPath = "/api/v1/auth/logout";
    private boolean secure = true;
    private String sameSite = "Strict";
    private List<String> allowedOrigins = new ArrayList<>();

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getLogoutPath() {
        return logoutPath;
    }

    public void setLogoutPath(String logoutPath) {
        this.logoutPath = logoutPath;
    }

    public boolean isSecure() {
        return secure;
    }

    public void setSecure(boolean secure) {
        this.secure = secure;
    }

    public String getSameSite() {
        return sameSite;
    }

    public void setSameSite(String sameSite) {
        this.sameSite = sameSite;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }
}
