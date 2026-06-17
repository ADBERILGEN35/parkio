package com.parkio.media.application.port;

/**
 * Port for an anti-malware scan of uploaded bytes before they are stored or served.
 * The concrete adapter (e.g. ClamAV over clamd) lives in infrastructure so the
 * application stays free of any scanner SDK or transport detail.
 *
 * <p><b>Fail-closed contract:</b> if the scan cannot be completed (scanner
 * unreachable, timeout, protocol/size error) the implementation MUST throw
 * {@link MediaScannerUnavailableException} rather than returning a (possibly
 * optimistic) verdict. The application treats that as "not safe to serve" and
 * rejects the upload — bytes never become {@code READY} without a clean scan.
 *
 * <p>This is a malware/abuse-payload check only; it is NOT illegal/abusive image
 * classification (CSAM etc.), which requires a dedicated provider and/or human
 * moderation.
 */
public interface MediaScanner {

    /**
     * Scans the given content. Returns a verdict for a completed scan (clean or
     * infected); throws {@link MediaScannerUnavailableException} when the scan could
     * not be completed at all.
     */
    ScanReport scan(byte[] content);

    /**
     * Result of a <em>completed</em> scan. {@code clean=false} means a signature was
     * matched; {@code signature} carries the matched name for audit (never shown to the
     * uploader), or {@code null} when clean.
     */
    record ScanReport(boolean clean, String signature) {

        public static ScanReport ofClean() {
            return new ScanReport(true, null);
        }

        public static ScanReport ofInfected(String signature) {
            return new ScanReport(false, signature);
        }
    }
}
