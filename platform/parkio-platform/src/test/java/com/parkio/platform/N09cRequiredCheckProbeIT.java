package com.parkio.platform;

import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Synthetic #418 probe on a throwaway PR that is never merged: a red integration job must block merge. */
@Tag("integration")
class N09cRequiredCheckProbeIT {

    @Test
    void failsOnPurpose() {
        fail("synthetic #418 probe: a red Backend integration job must block merge into api");
    }
}
