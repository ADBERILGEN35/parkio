package com.parkio.parking.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class TrustLedgerOrderTest {

    private static final UUID HIGH_BIT = UUID.fromString("90000000-0000-0000-0000-000000000000");
    private static final UUID LOW_BIT = UUID.fromString("10000000-0000-0000-0000-000000000000");

    @Test
    void unsignedUuidOrderMatchesPostgreSQLAndNotJavaSignedOrder() {
        assertThat(HIGH_BIT).isLessThan(LOW_BIT);
        assertThat(TrustLedgerOrder.UNSIGNED_ID.compare(LOW_BIT, HIGH_BIT)).isNegative();
        assertThat(TrustLedgerOrder.UNSIGNED_ID.compare(HIGH_BIT, LOW_BIT)).isPositive();
    }
}
