package com.parkio.media.infrastructure.storage;

import io.minio.MinioAsyncClient;
import io.minio.messages.Item;
import io.minio.messages.ListVersionsResult;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * ListObjectVersions, one request per call. The MinIO SDK's public listing is an iterator that
 * requests further pages on its own while the caller iterates, so a lookup whose pages hold no
 * match would keep requesting pages within one call, each under its own call timeout. This
 * exposes the SDK's single-request operation instead; the caller decides whether to list again.
 */
public final class VersionListingClient extends MinioAsyncClient {

    public VersionListingClient(MinioAsyncClient client) {
        super(client);
    }

    /**
     * One request: the first {@code maxKeys} versions and delete markers under {@code prefix}, in
     * key order (S3 lists keys in UTF-8 binary order, a key's versions newest first). Keys travel
     * URL-encoded and every entry, delete markers included, decodes its own.
     */
    ListVersionsResult firstPage(String bucket, String prefix, int maxKeys) throws Exception {
        try {
            ListVersionsResult page = listObjectVersionsAsync(bucket, null, null, "url", null, maxKeys, prefix, null,
                    null, null).get().result();
            for (Item item : page.contents()) {
                item.setEncodingType(page.encodingType());
            }
            for (Item item : page.deleteMarkers()) {
                item.setEncodingType(page.encodingType());
            }
            return page;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() instanceof CompletionException completion && completion.getCause() != null
                    ? completion.getCause() : e.getCause();
            throw cause instanceof Exception failure ? failure : e;
        }
    }
}
