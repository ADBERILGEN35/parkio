package com.parkio.auth.application.port;

/**
 * Captures the whole {@code erased_user_tombstones} ledger under the lock protocol: one READ
 * COMMITTED transaction that takes a SHARE lock on the table, reads every row, and commits. The
 * method returns only after that transaction committed; on a lock or statement timeout, or any
 * other failure, the transaction rolls back and the method throws, so nothing can be published.
 */
@FunctionalInterface
public interface ErasureLedgerCapture {

    CapturedErasureLedger capture();
}
