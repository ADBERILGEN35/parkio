package com.parkio.parking.application.port;

/**
 * Observability for Decision Audit Store appends. Implementations must not tag IDs.
 */
public interface DecisionAuditWriteObserver {

    void onWriteSuccess();

    void onWriteFailure();

    static DecisionAuditWriteObserver noop() {
        return new DecisionAuditWriteObserver() {
            @Override
            public void onWriteSuccess() {}

            @Override
            public void onWriteFailure() {}
        };
    }
}