package com.shoplab.helpdesk.retrieval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the upload of documents under {@code shoplab.retrieval.upload}.
 *
 * @param maxBytes largest document accepted, in bytes; the default is the limit of the ingest service (10 MiB)
 */
@ConfigurationProperties("shoplab.retrieval.upload")
public record UploadProperties(Integer maxBytes) {

    public static final int DEFAULT_MAX_BYTES = 10 * 1024 * 1024;

    public UploadProperties {
        maxBytes = maxBytes == null ? DEFAULT_MAX_BYTES : maxBytes;
        if (maxBytes < 1) {
            throw new IllegalArgumentException("shoplab.retrieval.upload.max-bytes must be at least 1 but was "
                    + maxBytes);
        }
    }
}
