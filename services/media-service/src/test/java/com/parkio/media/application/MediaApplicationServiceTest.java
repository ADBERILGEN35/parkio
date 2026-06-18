package com.parkio.media.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.parkio.media.application.command.UploadMediaCommand;
import com.parkio.media.application.port.MediaFileRepository;
import com.parkio.media.application.port.MediaScanner;
import com.parkio.media.application.port.MediaScannerUnavailableException;
import com.parkio.media.application.port.MediaStoragePort;
import com.parkio.media.application.port.MediaValidationResultRepository;
import com.parkio.media.application.port.OutboxEventAppender;
import com.parkio.media.application.result.MediaAccessUrl;
import com.parkio.media.application.result.MediaUploadResult;
import com.parkio.media.domain.MediaFile;
import com.parkio.media.domain.MediaStatus;
import com.parkio.media.domain.MediaValidationOutcome;
import com.parkio.media.domain.MediaValidationResult;
import com.parkio.media.domain.MediaValidationType;
import com.parkio.media.domain.event.MediaEvent;
import com.parkio.media.domain.event.MediaRejectedEvent;
import com.parkio.media.domain.event.MediaUploadedEvent;
import com.parkio.media.domain.exception.MediaErrorCode;
import com.parkio.media.domain.exception.MediaException;
import com.parkio.media.infrastructure.image.ImageIoImageNormalizer;
import com.parkio.media.shared.Checksums;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavioural unit tests for {@link MediaApplicationService} using in-memory fake
 * ports (incl. a fake storage adapter) — no Spring context, no database, no MinIO.
 */
class MediaApplicationServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-07T12:00:00Z");
    private static final long MAX_SIZE = 1_000_000;
    private static final Duration ACCESS_URL_TTL = Duration.ofMinutes(5);

    private FakeMediaFileRepository mediaFiles;
    private FakeMediaValidationResultRepository validationResults;
    private FakeMediaStoragePort storage;
    private FakeMediaScanner scanner;
    private FakeOutboxEventAppender outbox;
    private MediaApplicationService service;

    @BeforeEach
    void setUp() {
        mediaFiles = new FakeMediaFileRepository();
        validationResults = new FakeMediaValidationResultRepository();
        storage = new FakeMediaStoragePort();
        scanner = new FakeMediaScanner();
        outbox = new FakeOutboxEventAppender();
        MediaUploadConstraints constraints = new MediaUploadConstraints(
                Set.of("image/jpeg", "image/png", "image/webp"), MAX_SIZE, 1_000, 1_000, 1_000_000);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        service = new MediaApplicationService(mediaFiles, validationResults, storage,
                new ImageIoImageNormalizer(constraints), scanner, outbox,
                new MediaRejectionRecorder(outbox), constraints,
                new MediaAccessUrlPolicy(ACCESS_URL_TTL), clock);
    }

    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] WEBP_MAGIC =
            {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};

    /** Builds a command with a real JPEG image; {@code body} tweaks pixel color for duplicate tests. */
    private UploadMediaCommand jpeg(UUID owner, byte[] body) {
        return new UploadMediaCommand(owner, "image/jpeg", jpegBytes(16, 16, Arrays.hashCode(body)));
    }

    private static byte[] withMagic(byte[] magic, byte[] body) {
        byte[] out = new byte[magic.length + body.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(body, 0, out, magic.length, body.length);
        return out;
    }

    @Test
    void uploadStoresMetadataValidationResultsAndOutboxEvent() {
        UUID owner = UUID.randomUUID();

        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3, 4}));

        assertThat(result.status()).isEqualTo(MediaStatus.READY);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(result.fileSize()).isEqualTo(storage.objects.values().iterator().next().length);

        MediaFile stored = mediaFiles.findById(result.mediaId()).orElseThrow();
        assertThat(stored.ownerUserId()).isEqualTo(owner);
        assertThat(stored.checksum()).isNotBlank();
        assertThat(stored.objectKey()).startsWith("media/" + owner + "/");
        // The original filename is never used for the key.
        assertThat(stored.objectKey()).endsWith(".jpg");

        // Bytes landed in storage under the generated key.
        assertThat(storage.objects).containsKey(stored.objectKey());

        // Synchronous validations (incl. the malware scan) are recorded as PASSED.
        assertThat(validationResults.findByMediaId(result.mediaId()))
                .extracting(MediaValidationResult::validationType)
                .containsExactlyInAnyOrder(MediaValidationType.FILE_SIZE,
                        MediaValidationType.MIME_TYPE, MediaValidationType.DUPLICATE,
                        MediaValidationType.MALWARE_SCAN);
        assertThat(validationResults.findByMediaId(result.mediaId()))
                .allMatch(r -> r.result() == MediaValidationOutcome.PASSED);
        assertThat(scanner.scanCount).isEqualTo(1);

        assertThat(outbox.events).singleElement().isInstanceOf(MediaUploadedEvent.class);
    }

    @Test
    void uploadAcceptsValidPngByDetectedBytes() {
        UUID owner = UUID.randomUUID();

        MediaUploadResult result = service.upload(
                new UploadMediaCommand(owner, "image/png", pngBytes(16, 16)));

        assertThat(result.status()).isEqualTo(MediaStatus.READY);
        assertThat(result.contentType()).isEqualTo("image/jpeg");
        assertThat(mediaFiles.findById(result.mediaId()).orElseThrow().objectKey()).endsWith(".jpg");
    }

    @Test
    void uploadRejectsWebpWhenRuntimeReaderIsUnavailable() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(
                new UploadMediaCommand(owner, "image/webp", withMagic(WEBP_MAGIC, new byte[]{1, 2}))))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.INVALID_IMAGE);

        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.MIME_TYPE);
    }

    @Test
    void uploadRejectsDeclaredJpegWithNonImageBytes() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() ->
                service.upload(new UploadMediaCommand(owner, "image/jpeg", new byte[]{1, 2, 3, 4, 5})))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE);

        assertThat(mediaFiles.byId).isEmpty();
        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.MIME_TYPE);
    }

    @Test
    void uploadRejectsDeclaredPngButJpegBytes() {
        UUID owner = UUID.randomUUID();

        // Declared PNG, but the bytes are JPEG → conflict → 415.
        assertThatThrownBy(() ->
                service.upload(new UploadMediaCommand(owner, "image/png", jpegBytes(16, 16, 9))))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE);

        assertThat(mediaFiles.byId).isEmpty();
        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.MIME_TYPE);
    }

    @Test
    void uploadRejectsUnsupportedMimeType() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(new UploadMediaCommand(owner, "application/pdf", new byte[]{1, 2})))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.UNSUPPORTED_MEDIA_TYPE);

        assertThat(mediaFiles.byId).isEmpty();
        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.MIME_TYPE);
    }

    @Test
    void uploadRejectsFileExceedingMaxSize() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(
                new UploadMediaCommand(owner, "image/jpeg", withMagic(JPEG_MAGIC, new byte[(int) MAX_SIZE + 1]))))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.FILE_TOO_LARGE);

        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.FILE_SIZE);
    }

    @Test
    void uploadRejectsEmptyFile() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(new UploadMediaCommand(owner, "image/jpeg", new byte[0])))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.EMPTY_FILE);

        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.FILE_SIZE);
    }

    @Test
    void uploadRejectsDuplicateChecksum() {
        UUID owner = UUID.randomUUID();
        byte[] content = {9, 8, 7, 6, 5};
        service.upload(jpeg(owner, content));

        assertThatThrownBy(() -> service.upload(jpeg(owner, content)))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.DUPLICATE_MEDIA);

        // Only the first upload was stored; the duplicate was not re-stored.
        assertThat(storage.objects).hasSize(1);
        assertThat(mediaFiles.byId).hasSize(1);
        // One uploaded event + one rejected (duplicate) event.
        assertThat(outbox.events).hasSize(2);
        assertRejectedEvent(MediaValidationType.DUPLICATE);
    }

    @Test
    void uploadStripsJpegExifGpsMetadataAndStoresNormalizedBytesOnly() {
        UUID owner = UUID.randomUUID();
        byte[] original = jpegWithExifGpsMetadata();

        MediaUploadResult result = service.upload(new UploadMediaCommand(owner, "image/jpeg", original));

        MediaFile stored = mediaFiles.findById(result.mediaId()).orElseThrow();
        byte[] storedBytes = storage.objects.get(stored.objectKey());
        assertThat(asAscii(storedBytes)).doesNotContain("Exif", "GPS", "secret-device");
        assertThat(storedBytes).isNotEqualTo(original);
        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.fileSize()).isEqualTo(storedBytes.length);
    }

    @Test
    void uploadStripsPngTextMetadataByReencodingToJpeg() {
        UUID owner = UUID.randomUUID();
        byte[] original = pngWithTextMetadata("GPS", "secret-location");

        MediaUploadResult result = service.upload(new UploadMediaCommand(owner, "image/png", original));

        MediaFile stored = mediaFiles.findById(result.mediaId()).orElseThrow();
        byte[] storedBytes = storage.objects.get(stored.objectKey());
        assertThat(asAscii(storedBytes)).doesNotContain("GPS", "secret-location");
        assertThat(stored.contentType()).isEqualTo("image/jpeg");
        assertThat(stored.objectKey()).endsWith(".jpg");
    }

    @Test
    void uploadRejectsCorruptImageThatPassesMagicBytes() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(
                new UploadMediaCommand(owner, "image/jpeg", withMagic(JPEG_MAGIC, new byte[]{1, 2, 3, 4, 5}))))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.INVALID_IMAGE);

        assertThat(mediaFiles.byId).isEmpty();
        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.MIME_TYPE);
    }

    @Test
    void uploadRejectsImageWithOversizedDimensions() {
        UUID owner = UUID.randomUUID();

        assertThatThrownBy(() -> service.upload(
                new UploadMediaCommand(owner, "image/png", pngBytes(1_001, 20))))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.INVALID_IMAGE);

        assertThat(mediaFiles.byId).isEmpty();
        assertThat(storage.objects).isEmpty();
        assertRejectedEvent(MediaValidationType.MIME_TYPE);
    }

    @Test
    void checksumUsesNormalizedStoredBytes() {
        UUID owner = UUID.randomUUID();
        byte[] original = pngWithTextMetadata("GPS", "checksum-secret");

        MediaUploadResult result = service.upload(new UploadMediaCommand(owner, "image/png", original));

        MediaFile stored = mediaFiles.findById(result.mediaId()).orElseThrow();
        byte[] storedBytes = storage.objects.get(stored.objectKey());
        assertThat(stored.checksum()).isEqualTo(Checksums.sha256Hex(storedBytes));
        assertThat(stored.checksum()).isNotEqualTo(Checksums.sha256Hex(original));
    }

    @Test
    void malwareScanReceivesOriginalBytesBeforeNormalization() {
        UUID owner = UUID.randomUUID();
        byte[] original = pngWithTextMetadata("GPS", "scan-original");

        MediaUploadResult result = service.upload(new UploadMediaCommand(owner, "image/png", original));

        MediaFile stored = mediaFiles.findById(result.mediaId()).orElseThrow();
        assertThat(scanner.lastContent).isEqualTo(original);
        assertThat(storage.objects.get(stored.objectKey())).isNotEqualTo(original);
    }

    @Test
    void uploadRejectsInfectedFileWithoutStoring() {
        UUID owner = UUID.randomUUID();
        scanner.verdict = MediaScanner.ScanReport.ofInfected("Eicar-Test-Signature");

        assertThatThrownBy(() -> service.upload(jpeg(owner, new byte[]{1, 2, 3, 4})))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_INFECTED);

        // Infected bytes never reach storage and no media row is persisted.
        assertThat(storage.objects).isEmpty();
        assertThat(mediaFiles.byId).isEmpty();
        assertRejectedEvent(MediaValidationType.MALWARE_SCAN);
    }

    @Test
    void uploadFailsClosedWhenScannerUnavailable() {
        UUID owner = UUID.randomUUID();
        scanner.unavailable = true;

        assertThatThrownBy(() -> service.upload(jpeg(owner, new byte[]{1, 2, 3, 4})))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_SCAN_UNAVAILABLE);

        // Fail closed: nothing stored, nothing persisted — media never becomes READY.
        assertThat(storage.objects).isEmpty();
        assertThat(mediaFiles.byId).isEmpty();
        assertRejectedEvent(MediaValidationType.MALWARE_SCAN);
    }

    @Test
    void ownerCanReadMetadata() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3}));

        MediaFile media = service.getMetadata(result.mediaId(), owner, false);

        assertThat(media.id()).isEqualTo(result.mediaId());
        assertThat(media.ownerUserId()).isEqualTo(owner);
    }

    @Test
    void nonOwnerMetadataReadIsAnsweredAsNotFound() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3}));

        assertThatThrownBy(() -> service.getMetadata(result.mediaId(), UUID.randomUUID(), false))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    void moderatorCanReadAnotherUsersMetadata() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3}));

        MediaFile media = service.getMetadata(result.mediaId(), UUID.randomUUID(), true);

        assertThat(media.id()).isEqualTo(result.mediaId());
    }

    @Test
    void getMetadataThrowsWhenMissing() {
        UUID requester = UUID.randomUUID();
        assertThatThrownBy(() -> service.getMetadata(UUID.randomUUID(), requester, false))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    void ownerObtainsShortLivedAccessUrlThatIsNeverPersisted() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3}));
        String objectKey = mediaFiles.findById(result.mediaId()).orElseThrow().objectKey();

        MediaAccessUrl accessUrl = service.createAccessUrl(result.mediaId(), owner, false);

        assertThat(accessUrl.mediaId()).isEqualTo(result.mediaId());
        assertThat(accessUrl.url()).isEqualTo("https://signed.example/" + objectKey + "?ttl=" + ACCESS_URL_TTL);
        assertThat(accessUrl.expiresAt()).isEqualTo(NOW.plus(ACCESS_URL_TTL));
        // Generated per request — nothing about the URL is stored on the aggregate.
        assertThat(storage.presignedKeys).containsExactly(objectKey);
    }

    @Test
    void accessUrlDeniedForNonReadyMedia() {
        UUID owner = UUID.randomUUID();
        // A media row that has not passed the scan yet (PENDING_SCAN), saved directly.
        MediaFile pending = MediaFile.create(owner, "bucket", "media/" + owner + "/x.jpg",
                "image/jpeg", 10, "checksum-pending", null, NOW);
        mediaFiles.save(pending);

        // Even the owner cannot get a signed URL until the media is READY; answered as NOT_FOUND.
        assertThatThrownBy(() -> service.createAccessUrl(pending.id(), owner, false))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
        // The internal (parking-mediated) path is also denied for non-READY media.
        assertThatThrownBy(() -> service.createAccessUrlForInternalCaller(pending.id()))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
        assertThat(storage.presignedKeys).isEmpty();
    }

    @Test
    void internalStatusReportsLifecycleState() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult ready = service.upload(jpeg(owner, new byte[]{1, 2, 3}));
        assertThat(service.getStatusForInternalCaller(ready.mediaId())).isEqualTo(MediaStatus.READY);

        MediaFile pending = MediaFile.create(owner, "bucket", "media/" + owner + "/y.jpg",
                "image/jpeg", 10, "checksum-pending2", null, NOW);
        mediaFiles.save(pending);
        assertThat(service.getStatusForInternalCaller(pending.id())).isEqualTo(MediaStatus.PENDING_SCAN);

        assertThatThrownBy(() -> service.getStatusForInternalCaller(UUID.randomUUID()))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    void internalAttachmentStatusRequiresOwnerAndPreservesLifecycleState() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult ready = service.upload(jpeg(owner, new byte[]{1, 2, 3}));
        assertThat(service.getStatusForInternalAttachment(ready.mediaId(), owner)).isEqualTo(MediaStatus.READY);

        assertThatThrownBy(() -> service.getStatusForInternalAttachment(ready.mediaId(), UUID.randomUUID()))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);

        MediaFile pending = MediaFile.create(owner, "bucket", "media/" + owner + "/pending.jpg",
                "image/jpeg", 10, "checksum-pending-owner", null, NOW);
        mediaFiles.save(pending);
        assertThat(service.getStatusForInternalAttachment(pending.id(), owner)).isEqualTo(MediaStatus.PENDING_SCAN);

        service.delete(ready.mediaId(), owner);
        assertThatThrownBy(() -> service.getStatusForInternalAttachment(ready.mediaId(), owner))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    void nonOwnerCannotObtainAccessUrl() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3}));

        assertThatThrownBy(() -> service.createAccessUrl(result.mediaId(), UUID.randomUUID(), false))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
        assertThat(storage.presignedKeys).isEmpty();
    }

    @Test
    void moderatorCanObtainAccessUrl() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 2, 3}));

        MediaAccessUrl accessUrl = service.createAccessUrl(result.mediaId(), UUID.randomUUID(), true);

        assertThat(accessUrl.url()).isNotBlank();
    }

    @Test
    void deleteRejectsNonOwnerAndSoftDeletesForOwner() {
        UUID owner = UUID.randomUUID();
        UUID otherUser = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{4, 5, 6}));
        String objectKey = mediaFiles.findById(result.mediaId()).orElseThrow().objectKey();

        assertThatThrownBy(() -> service.delete(result.mediaId(), otherUser))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.NOT_MEDIA_OWNER);

        service.delete(result.mediaId(), owner);

        // Soft-deleted: no longer retrievable, object removed from storage.
        assertThat(mediaFiles.byId.get(result.mediaId()).status()).isEqualTo(MediaStatus.DELETED);
        assertThat(storage.objects).doesNotContainKey(objectKey);
        assertThat(storage.deleted).contains(objectKey);
        assertThatThrownBy(() -> service.getMetadata(result.mediaId(), owner, false))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    void ownerCanReadValidationResults() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 1, 1}));

        List<MediaValidationResult> results = service.getValidationResults(result.mediaId(), owner, false);

        assertThat(results).hasSize(4);
        assertThat(results).allMatch(r -> r.result() == MediaValidationOutcome.PASSED);
    }

    @Test
    void normalNonOwnerCannotReadValidationResults() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 1, 1}));

        assertThatThrownBy(() -> service.getValidationResults(result.mediaId(), UUID.randomUUID(), false))
                .isInstanceOf(MediaException.class)
                .extracting(e -> ((MediaException) e).errorCode())
                .isEqualTo(MediaErrorCode.MEDIA_NOT_FOUND);
    }

    @Test
    void moderatorCanReadValidationResults() {
        UUID owner = UUID.randomUUID();
        MediaUploadResult result = service.upload(jpeg(owner, new byte[]{1, 1, 1}));

        List<MediaValidationResult> results =
                service.getValidationResults(result.mediaId(), UUID.randomUUID(), true);

        assertThat(results).hasSize(4);
    }

    private void assertRejectedEvent(MediaValidationType expectedType) {
        assertThat(outbox.events).filteredOn(e -> e instanceof MediaRejectedEvent)
                .singleElement()
                .satisfies(e -> assertThat(((MediaRejectedEvent) e).validationType()).isEqualTo(expectedType));
    }

    private static byte[] jpegBytes(int width, int height, int seed) {
        return writeImage("jpeg", image(width, height, seed));
    }

    private static byte[] pngBytes(int width, int height) {
        return writeImage("png", image(width, height, 17));
    }

    private static BufferedImage image(int width, int height, int seed) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(seed | 0xFF000000));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(Color.WHITE);
            graphics.drawLine(0, 0, width - 1, height - 1);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static byte[] writeImage(String format, BufferedImage image) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No ImageIO writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static byte[] jpegWithExifGpsMetadata() {
        byte[] jpeg = jpegBytes(16, 16, 42);
        byte[] payload = "Exif\0\0GPS secret-device".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        int segmentLength = payload.length + 2;
        byte[] output = new byte[jpeg.length + payload.length + 4];
        output[0] = (byte) 0xFF;
        output[1] = (byte) 0xD8;
        output[2] = (byte) 0xFF;
        output[3] = (byte) 0xE1;
        output[4] = (byte) ((segmentLength >>> 8) & 0xFF);
        output[5] = (byte) (segmentLength & 0xFF);
        System.arraycopy(payload, 0, output, 6, payload.length);
        System.arraycopy(jpeg, 2, output, payload.length + 6, jpeg.length - 2);
        return output;
    }

    private static byte[] pngWithTextMetadata(String keyword, String value) {
        byte[] png = pngBytes(16, 16);
        byte[] text = (keyword + '\0' + value).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        byte[] chunk = pngChunk("tEXt", text);
        byte[] output = new byte[png.length + chunk.length];
        int insertAt = PNG_MAGIC.length + 25;
        System.arraycopy(png, 0, output, 0, insertAt);
        System.arraycopy(chunk, 0, output, insertAt, chunk.length);
        System.arraycopy(png, insertAt, output, insertAt + chunk.length, png.length - insertAt);
        return output;
    }

    private static byte[] pngChunk(String type, byte[] data) {
        byte[] typeBytes = type.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((data.length >>> 24) & 0xFF);
        out.write((data.length >>> 16) & 0xFF);
        out.write((data.length >>> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.writeBytes(typeBytes);
        out.writeBytes(data);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        long value = crc.getValue();
        out.write((int) ((value >>> 24) & 0xFF));
        out.write((int) ((value >>> 16) & 0xFF));
        out.write((int) ((value >>> 8) & 0xFF));
        out.write((int) (value & 0xFF));
        return out.toByteArray();
    }

    private static String asAscii(byte[] bytes) {
        return new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    // --- Fakes -----------------------------------------------------------

    private static final class FakeMediaFileRepository implements MediaFileRepository {
        private final Map<UUID, MediaFile> byId = new HashMap<>();

        @Override
        public MediaFile save(MediaFile media) {
            byId.put(media.id(), media);
            return media;
        }

        @Override
        public Optional<MediaFile> findById(UUID id) {
            return Optional.ofNullable(byId.get(id));
        }

        @Override
        public boolean existsByChecksum(String checksum) {
            return byId.values().stream().anyMatch(m -> m.checksum().equals(checksum));
        }
    }

    private static final class FakeMediaValidationResultRepository implements MediaValidationResultRepository {
        private final List<MediaValidationResult> all = new ArrayList<>();

        @Override
        public MediaValidationResult save(MediaValidationResult result) {
            all.add(result);
            return result;
        }

        @Override
        public List<MediaValidationResult> findByMediaId(UUID mediaId) {
            return all.stream().filter(r -> r.mediaId().equals(mediaId)).toList();
        }
    }

    private static final class FakeMediaStoragePort implements MediaStoragePort {
        private final Map<String, byte[]> objects = new HashMap<>();
        private final List<String> deleted = new ArrayList<>();
        private final List<String> presignedKeys = new ArrayList<>();

        @Override
        public StoredObject store(String objectKey, byte[] content, String contentType) {
            objects.put(objectKey, content);
            return new StoredObject("test-bucket", objectKey);
        }

        @Override
        public void delete(String objectKey) {
            objects.remove(objectKey);
            deleted.add(objectKey);
        }

        @Override
        public String generatePresignedGetUrl(String objectKey, Duration ttl) {
            presignedKeys.add(objectKey);
            return "https://signed.example/" + objectKey + "?ttl=" + ttl;
        }
    }

    private static final class FakeMediaScanner implements MediaScanner {
        private int scanCount;
        private boolean unavailable;
        private ScanReport verdict = ScanReport.ofClean();

        @Override
        public ScanReport scan(byte[] content) {
            scanCount++;
            lastContent = content;
            if (unavailable) {
                throw new MediaScannerUnavailableException("scanner down (test)");
            }
            return verdict;
        }

        private byte[] lastContent;
    }

    private static final class FakeOutboxEventAppender implements OutboxEventAppender {
        private final List<MediaEvent> events = new ArrayList<>();

        @Override
        public void append(MediaEvent event) {
            events.add(event);
        }
    }
}
