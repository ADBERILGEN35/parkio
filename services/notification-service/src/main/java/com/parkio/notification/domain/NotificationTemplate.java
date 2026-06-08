package com.parkio.notification.domain;

import java.util.Map;
import java.util.Objects;

/**
 * Content template for a notification type. {@code titleTemplate}/{@code bodyTemplate}
 * may contain {@code {placeholder}} tokens replaced from a variable map. Pure domain.
 */
public final class NotificationTemplate {

    private final NotificationType type;
    private final String titleTemplate;
    private final String bodyTemplate;

    public NotificationTemplate(NotificationType type, String titleTemplate, String bodyTemplate) {
        this.type = Objects.requireNonNull(type, "type");
        this.titleTemplate = Objects.requireNonNull(titleTemplate, "titleTemplate");
        this.bodyTemplate = Objects.requireNonNull(bodyTemplate, "bodyTemplate");
    }

    /** Rendered title/body produced by substituting variables into the templates. */
    public record RenderedContent(String title, String body) {
    }

    public RenderedContent render(Map<String, String> variables) {
        return new RenderedContent(substitute(titleTemplate, variables), substitute(bodyTemplate, variables));
    }

    private static String substitute(String template, Map<String, String> variables) {
        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            result = result.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return result;
    }

    public NotificationType type() {
        return type;
    }

    public String titleTemplate() {
        return titleTemplate;
    }

    public String bodyTemplate() {
        return bodyTemplate;
    }
}
