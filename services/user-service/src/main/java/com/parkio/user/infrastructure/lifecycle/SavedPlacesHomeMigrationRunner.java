package com.parkio.user.infrastructure.lifecycle;

import com.parkio.user.application.SavedPlaceApplicationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Idempotent Smart Return HOME → SavedPlace(HOME) backfill (WP-SPA-03).
 * Controlled by {@code parkio.spa.saved-places.migration-enabled}.
 */
@Component
public class SavedPlacesHomeMigrationRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SavedPlacesHomeMigrationRunner.class);

    private final SavedPlaceApplicationService savedPlaces;
    private final boolean migrationEnabled;
    private final int batchSize;

    public SavedPlacesHomeMigrationRunner(
            SavedPlaceApplicationService savedPlaces,
            @Value("${parkio.spa.saved-places.migration-enabled:false}") boolean migrationEnabled,
            @Value("${parkio.spa.saved-places.migration-batch-size:200}") int batchSize) {
        this.savedPlaces = savedPlaces;
        this.migrationEnabled = migrationEnabled;
        this.batchSize = batchSize;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!migrationEnabled) {
            return;
        }
        int total = 0;
        int loops = 0;
        while (loops < 10_000) {
            int migrated = savedPlaces.backfillLegacyHomes(batchSize);
            total += migrated;
            loops++;
            if (migrated == 0) {
                break;
            }
        }
        log.info("saved-places home migration finished totalMigrated={}", total);
    }
}
