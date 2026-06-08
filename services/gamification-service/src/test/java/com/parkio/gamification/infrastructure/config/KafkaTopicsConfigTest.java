package com.parkio.gamification.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.junit.jupiter.api.Test;

/** Verifies this service's Kafka topic provisioning (name, partitions, replicas, retention). */
class KafkaTopicsConfigTest {

    private final KafkaTopicsConfig config = new KafkaTopicsConfig(1);

    @Test
    void provisionsGamificationScoreTopicAsHot30Day() {
        NewTopic t = config.gamificationScoreTopic();
        assertThat(t.name()).isEqualTo("parkio.gamification.score");
        assertThat(t.numPartitions()).isEqualTo(6);
        assertThat(t.replicationFactor()).isEqualTo((short) 1);
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "2592000000");
    }

    @Test
    void provisionsGamificationDltTopic() {
        NewTopic t = config.gamificationDltTopic();
        assertThat(t.name()).isEqualTo("parkio.dlt.gamification");
        assertThat(t.configs()).containsEntry(TopicConfig.RETENTION_MS_CONFIG, "1209600000");
    }
}
