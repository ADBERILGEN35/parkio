package com.parkio.aivalidation.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AiValidationKafkaConsumerConfigTest {

    @Test
    void defaultBatchOfOneIsSafeUnderModeledWorstCase() {
        assertThat(AiValidationKafkaConsumerConfig.isPollBatchSafe(
                1, AiValidationKafkaConsumerConfig.MODELED_WORST_CASE_RECORD_MS, 180_000L))
                .isTrue();
    }

    @Test
    void batchOfHundredIsUnsafeAt180sInterval() {
        assertThat(AiValidationKafkaConsumerConfig.isPollBatchSafe(
                100, AiValidationKafkaConsumerConfig.MODELED_WORST_CASE_RECORD_MS, 180_000L))
                .isFalse();
    }

    @Test
    void maxSafePollRecordsCapsBatchUnderInterval() {
        assertThat(AiValidationKafkaConsumerConfig.maxSafePollRecords(45_000L, 180_000L))
                .isEqualTo(3);
        assertThat(AiValidationKafkaConsumerConfig.isPollBatchSafe(
                3, 45_000L, 180_000L)).isTrue();
        assertThat(AiValidationKafkaConsumerConfig.isPollBatchSafe(
                4, 45_000L, 180_000L)).isFalse();
    }

    @Test
    void constructorRejectsUnsafePollConfiguration() {
        assertThatThrownBy(() -> new AiValidationKafkaConsumerConfig(
                "localhost:9092", false, 100, 180_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unsafe Kafka poll config");
    }

    @Test
    void constructorAcceptsSafeDefaults() {
        AiValidationKafkaConsumerConfig config =
                new AiValidationKafkaConsumerConfig("localhost:9092", false, 1, 180_000);
        assertThat(config).isNotNull();
    }
}
