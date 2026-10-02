package com.parkio.auth.application.durable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.auth.application.durable.DurableErasureEvidenceVerifier.VerifiedCheckpoint;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Consumer freshness for checkpoints: after a newer checkpoint was accepted, an older valid one
 * is refused, and so is a different ledger at the accepted sequence. The checkpoints are real
 * format v1 objects that pass the verifier.
 */
class CheckpointWatermarkTest {

    private static final String DATABASE = "auth-db:checkpoint-watermark-test";
    private static final ProducerKey PRODUCER =
            new ProducerKey("checkpoint-watermark-test", "watermark-test-key-not-a-secret".getBytes(StandardCharsets.UTF_8));
    private static final DurableErasureEvidenceVerifier VERIFIER =
            new DurableErasureEvidenceVerifier(DATABASE, Map.of(PRODUCER.producerId(), PRODUCER.key()));
    private static final UUID FIRST_USER = UUID.fromString("0f0e0d0c-0000-4000-8000-0000000000a1");
    private static final UUID SECOND_USER = UUID.fromString("0f0e0d0c-0000-4000-8000-0000000000a2");

    @Test
    void aNewerCheckpointIsAccepted() {
        CheckpointWatermark mark = CheckpointWatermark.first(checkpoint(2, FIRST_USER));

        CheckpointWatermark next = mark.accept(checkpoint(5, FIRST_USER, SECOND_USER));

        assertThat(next.sequence()).isEqualTo(5);
        assertThat(next.ledgerDigest()).isEqualTo(checkpoint(5, FIRST_USER, SECOND_USER).ledgerDigest());
    }

    @Test
    void anOlderValidCheckpointIsRefusedAfterANewerAccept() {
        VerifiedCheckpoint older = checkpoint(2, FIRST_USER);
        CheckpointWatermark mark = CheckpointWatermark.first(older).accept(checkpoint(5, FIRST_USER, SECOND_USER));

        assertThatThrownBy(() -> mark.accept(older))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("older valid checkpoint replayed; freshness failed");
    }

    @Test
    void theSameCheckpointAgainIsAcceptedButAnotherLedgerAtItsSequenceIsNot() {
        CheckpointWatermark mark = CheckpointWatermark.first(checkpoint(5, FIRST_USER, SECOND_USER));

        assertThat(mark.accept(checkpoint(5, FIRST_USER, SECOND_USER))).isEqualTo(mark);
        assertThatThrownBy(() -> mark.accept(checkpoint(5, FIRST_USER)))
                .isInstanceOf(DurableEvidenceException.class)
                .hasMessage("conflicting checkpoint at the same sequence");
    }

    private static VerifiedCheckpoint checkpoint(long sequence, UUID... users) {
        List<ErasureLedgerEntry> entries = Arrays.stream(users)
                .map(user -> new ErasureLedgerEntry(user, Instant.parse("2026-09-29T08:16:00Z")))
                .toList();
        return VERIFIER.verifyCheckpoint(DurableErasureEvidence.checkpoint(sequence, entries, DATABASE, PRODUCER));
    }
}
