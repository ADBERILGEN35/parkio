package com.parkio.auth.application.durable;

/**
 * Invalid or untrusted durable erasure evidence: the Java counterpart of the Python model's
 * {@code ContractError}. Messages match the Python messages so both verifiers can be checked
 * against the same expected outcomes.
 */
public class DurableEvidenceException extends RuntimeException {

    public DurableEvidenceException(String message) {
        super(message);
    }
}
