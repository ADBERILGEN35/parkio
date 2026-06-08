package com.parkio.user.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/** Verifies this service's Kafka topic provisioning (name, partitions, replicas, retention). */
class KafkaTopicsConfigTest {

    private final KafkaTopicsConfig config = new KafkaTopicsConfig(1);

    @Test
    void provisionsUserProfileTopic() {
        NewTopic t = config.userProfileTopic();
        assertThat(t.name()).isEqualTo("parkio.user.profile");
        assertThat(t.numPartitions()).isEqualTo(3);
        assertThat(t.replicationFactor()).isEqualTo((short) 1);
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "604800000");
    }

    @Test
    void provisionsUserDltTopic() {
        NewTopic t = config.userDltTopic();
        assertThat(t.name()).isEqualTo("parkio.dlt.user");
        assertThat(t.numPartitions()).isEqualTo(3);
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "1209600000");
    }
}
