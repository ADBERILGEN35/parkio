package com.parkio.analytics.application.port;

import com.parkio.analytics.domain.AnalyticsEvent;

/** Persistence port for the raw {@link AnalyticsEvent} audit log (append-only). */
public interface AnalyticsEventRepository {

    AnalyticsEvent save(AnalyticsEvent event);
}
