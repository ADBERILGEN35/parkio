package com.parkio.media.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/** Verifies this service's Kafka topic provisioning (name, partitions, replicas, retention). */
class KafkaTopicsConfigTest {

    private final KafkaTopicsConfig config = new KafkaTopicsConfig(1);

    @Test
    void provisionsMediaTopicAsHot7Day() {
        NewTopic t = config.mediaTopic();
        assertThat(t.name()).isEqualTo("parkio.media.media");
        assertThat(t.numPartitions()).isEqualTo(6);
        assertThat(t.replicationFactor()).isEqualTo((short) 1);
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");
    }
}
