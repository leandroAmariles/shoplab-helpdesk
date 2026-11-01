package com.shoplab.helpdesk.retrieval.config;

import com.shoplab.helpdesk.retrieval.domain.model.constants.RetrievalConstants;
import com.shoplab.helpdesk.retrieval.domain.model.enums.ReindexMode;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the embeddings step under {@code shoplab.retrieval.embedding}. The model itself is configured once, in
 * {@code spring.ai.ollama.embedding.options.model}, and its name is what gets stored with each vector.
 *
 * @param batchSize        texts sent to the model in one call (at least 1)
 * @param searchLimit      results of a search when the caller does not say (from 1 to 50)
 * @param autoIndex        whether documents are indexed right after an ingestion and at start-up
 * @param reindexOnStartup {@code false}, {@code true} (documents with chunks from another model) or {@code force}
 */
@ConfigurationProperties("shoplab.retrieval.embedding")
public record EmbeddingProperties(Integer batchSize, Integer searchLimit, Boolean autoIndex,
        String reindexOnStartup) {

    private static final int DEFAULT_BATCH_SIZE = 16;
    private static final int DEFAULT_SEARCH_LIMIT = 5;

    public EmbeddingProperties {
        batchSize = batchSize == null ? DEFAULT_BATCH_SIZE : batchSize;
        searchLimit = searchLimit == null ? DEFAULT_SEARCH_LIMIT : searchLimit;
        autoIndex = autoIndex == null || autoIndex;
        reindexOnStartup = reindexOnStartup == null ? "false" : reindexOnStartup;
        if (batchSize < 1) {
            throw new IllegalArgumentException("shoplab.retrieval.embedding.batch-size must be at least 1 but was "
                    + batchSize);
        }
        if (searchLimit < 1 || searchLimit > RetrievalConstants.MAX_SEARCH_LIMIT) {
            throw new IllegalArgumentException("shoplab.retrieval.embedding.search-limit must be between 1 and "
                    + RetrievalConstants.MAX_SEARCH_LIMIT + " but was " + searchLimit);
        }
        ReindexMode.fromSetting(reindexOnStartup);
    }

    public ReindexMode reindexMode() {
        return ReindexMode.fromSetting(reindexOnStartup);
    }
}
