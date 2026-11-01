package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.domain.model.constants.ChatConstants;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the retrieval-augmented answers under {@code shoplab.chat.rag}.
 *
 * @param topK                 fragments asked of the search for each question (from 1 to 20)
 * @param similarityThreshold  lowest similarity a fragment may have (from 0.0 to 1.0, default 0.6); 0.0 keeps
 *                             everything
 * @param maxContextCharacters most characters of context handed to the model (at least 500)
 * @param contextTemplate      user message with context; it has the query and context markers
 * @param emptyContextTemplate user message when there is no context; it has the query marker
 */
@ConfigurationProperties("shoplab.chat.rag")
public record RagProperties(Integer topK, Double similarityThreshold, Integer maxContextCharacters,
        String contextTemplate, String emptyContextTemplate) {

    public static final int MAX_TOP_K = 20;

    private static final int DEFAULT_TOP_K = 4;
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.6;
    private static final int DEFAULT_MAX_CONTEXT_CHARACTERS = 8000;
    private static final int MIN_CONTEXT_CHARACTERS = 500;

    public RagProperties {
        topK = topK == null ? DEFAULT_TOP_K : topK;
        similarityThreshold = similarityThreshold == null ? DEFAULT_SIMILARITY_THRESHOLD : similarityThreshold;
        maxContextCharacters = maxContextCharacters == null ? DEFAULT_MAX_CONTEXT_CHARACTERS : maxContextCharacters;
        contextTemplate = contextTemplate == null || contextTemplate.isBlank()
                ? ChatConstants.DEFAULT_CONTEXT_TEMPLATE : contextTemplate;
        emptyContextTemplate = emptyContextTemplate == null || emptyContextTemplate.isBlank()
                ? ChatConstants.DEFAULT_EMPTY_CONTEXT_TEMPLATE : emptyContextTemplate;
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new IllegalArgumentException("shoplab.chat.rag.top-k must be between 1 and " + MAX_TOP_K
                    + " but was " + topK);
        }
        if (similarityThreshold < 0.0 || similarityThreshold > 1.0) {
            throw new IllegalArgumentException("shoplab.chat.rag.similarity-threshold must be between 0.0 and 1.0 "
                    + "but was " + similarityThreshold);
        }
        if (maxContextCharacters < MIN_CONTEXT_CHARACTERS) {
            throw new IllegalArgumentException("shoplab.chat.rag.max-context-characters must be at least "
                    + MIN_CONTEXT_CHARACTERS + " but was " + maxContextCharacters);
        }
        if (!contextTemplate.contains(ChatConstants.QUERY_PLACEHOLDER)
                || !contextTemplate.contains(ChatConstants.CONTEXT_PLACEHOLDER)) {
            throw new IllegalArgumentException("shoplab.chat.rag.context-template must contain "
                    + ChatConstants.QUERY_PLACEHOLDER + " and " + ChatConstants.CONTEXT_PLACEHOLDER);
        }
        if (!emptyContextTemplate.contains(ChatConstants.QUERY_PLACEHOLDER)) {
            throw new IllegalArgumentException("shoplab.chat.rag.empty-context-template must contain "
                    + ChatConstants.QUERY_PLACEHOLDER);
        }
    }
}
