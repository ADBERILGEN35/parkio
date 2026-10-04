-- U02 restore replay (docs/architecture/erasure-restore-replay-contract.md): the participants an
-- attempt requires are fixed when it starts, so a later configuration change cannot change what
-- COMPLETE means for that attempt. The set is auth plus every contract participant
-- (docs/operations/recovery-evidence-contract.md §3); restore ACKs are stored only for a
-- participant the attempt requires.
CREATE TABLE erasure_restore_attempt_participants (
    recovery_attempt_id UUID        NOT NULL,
    service_name        VARCHAR(64) NOT NULL,
    CONSTRAINT pk_erasure_restore_attempt_participants PRIMARY KEY (recovery_attempt_id, service_name),
    CONSTRAINT fk_erasure_restore_attempt_participants_attempt
        FOREIGN KEY (recovery_attempt_id) REFERENCES erasure_restore_attempts (recovery_attempt_id)
);

-- NOT VALID: rows written before this migration (only possible on a database that ran V26
-- alone, with restore replay enabled) are not rechecked; every new ACK row is.
ALTER TABLE erasure_restore_acks
    ADD CONSTRAINT fk_erasure_restore_acks_required_participant
        FOREIGN KEY (recovery_attempt_id, service_name)
        REFERENCES erasure_restore_attempt_participants (recovery_attempt_id, service_name)
        NOT VALID;
