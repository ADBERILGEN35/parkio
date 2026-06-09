package com.parkio.notification.infrastructure.delivery;

import com.parkio.notification.application.port.PushMessage;
import com.parkio.notification.application.port.PushNotificationSender;
import com.parkio.notification.application.port.PushSendResult;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local/dev/test push sender. Does not contact any provider and requires no
 * credentials — it always "delivers" and returns a synthetic provider message id.
 * Active by default ({@code provider=noop}); the property is matched if missing so
 * the service runs out of the box without Firebase/APNS configuration.
 *
 * <p>Never logs the device token (ai-context/07).
 */
@Component
@ConditionalOnProperty(name = "parkio.notification.delivery.push.provider", havingValue = "noop",
        matchIfMissing = true)
public class NoopPushNotificationSender implements PushNotificationSender {

    private static final Logger log = LoggerFactory.getLogger(NoopPushNotificationSender.class);

    @Override
    public PushSendResult send(PushMessage message) {
        String providerMessageId = "noop-" + UUID.randomUUID();
        log.debug("No-op push delivery: platform={} title='{}' providerMessageId={}",
                message.platform(), message.title(), providerMessageId);
        return PushSendResult.sent(providerMessageId);
    }
}
