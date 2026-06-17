package com.parkio.media.infrastructure.scanner;

import com.parkio.media.application.port.MediaScanner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pass-through scanner used only when scanning is explicitly disabled
 * ({@code parkio.media.scanner.enabled=false}) — local dev without a ClamAV
 * container, and the H2-based unit/contract tests.
 *
 * <p><b>Never enable this in any environment that serves real users.</b> It reports
 * every upload as clean and therefore provides no protection. Production and hosted
 * beta must run the real {@link ClamavMediaScanner}.
 */
public class NoOpMediaScanner implements MediaScanner {

    private static final Logger log = LoggerFactory.getLogger(NoOpMediaScanner.class);

    public NoOpMediaScanner() {
        log.warn("Media malware scanning is DISABLED (NoOpMediaScanner). "
                + "Uploads are NOT scanned — use only for local dev/tests.");
    }

    @Override
    public ScanReport scan(byte[] content) {
        return ScanReport.ofClean();
    }
}
