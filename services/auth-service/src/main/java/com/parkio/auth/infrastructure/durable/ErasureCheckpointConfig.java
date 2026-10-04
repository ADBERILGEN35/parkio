package com.parkio.auth.infrastructure.durable;

import com.parkio.auth.application.ErasureCheckpointProducer;
import com.parkio.auth.application.port.DurableErasureCheckpointStore;
import com.parkio.auth.infrastructure.persistence.JdbcErasureLedgerCapture;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Wires {@link ErasureCheckpointProducer} only when
 * {@code parkio.privacy.account-erasure.durable-store.checkpoint.enabled=true}. Checkpoints are
 * published to the object-lock store, so enabling them without that store, or with an invalid
 * timeout, stops startup. No scheduler is wired: nothing calls the producer yet.
 */
@Configuration
@EnableConfigurationProperties(ErasureCheckpointProperties.class)
@ConditionalOnProperty(name = "parkio.privacy.account-erasure.durable-store.checkpoint.enabled", havingValue = "true")
class ErasureCheckpointConfig {

    @Bean
    ErasureCheckpointProducer erasureCheckpointProducer(ErasureCheckpointProperties properties,
                                                        ObjectProvider<DurableErasureCheckpointStore> stores,
                                                        JdbcTemplate jdbc,
                                                        PlatformTransactionManager transactionManager) {
        List<String> problems = properties.problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "parkio.privacy.account-erasure.durable-store.checkpoint is enabled but invalid: "
                            + String.join(", ", problems));
        }
        DurableErasureCheckpointStore store = stores.getIfAvailable();
        if (store == null) {
            throw new IllegalStateException(
                    "parkio.privacy.account-erasure.durable-store.checkpoint.enabled needs the object-lock store "
                            + "(parkio.privacy.account-erasure.durable-store.object-lock.enabled=true)");
        }
        return new ErasureCheckpointProducer(store, new JdbcErasureLedgerCapture(
                jdbc, transactionManager, properties.getLockTimeout(), properties.getStatementTimeout()));
    }
}
