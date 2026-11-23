package com.shoplab.helpdesk.chat.domain.model.constants;

public final class ChatConstants {

    public static final String SOURCES_EVENT = "sources";
    public static final String TOKEN_EVENT = "token";
    public static final String DONE_EVENT = "done";
    public static final String ERROR_EVENT = "error";

    public static final String FIRST_TOKEN_METRIC = "shoplab.chat.first.token";
    public static final String RESPONSE_METRIC = "shoplab.chat.response";
    public static final String OUTCOME_TAG = "outcome";
    public static final String CONTEXT_RETRIEVAL_METRIC = "shoplab.chat.context.retrieval";
    public static final String CONTEXT_FRAGMENTS_METRIC = "shoplab.chat.context.fragments";
    public static final String CITATIONS_METRIC = "shoplab.chat.citations";
    public static final String HISTORY_MESSAGES_METRIC = "shoplab.chat.history.messages";
    public static final String HISTORY_TRIMMED_METRIC = "shoplab.chat.history.trimmed";
    public static final String MEMORY_FAILURES_METRIC = "shoplab.chat.memory.failures";
    public static final String OPERATION_TAG = "operation";
    public static final String TURNS_METRIC = "shoplab.chat.turns";
    public static final String KIND_TAG = "kind";
    public static final String FEEDBACK_METRIC = "shoplab.chat.feedback";
    public static final String FEEDBACK_PROMOTED_METRIC = "shoplab.chat.feedback.promoted";
    public static final String RATING_TAG = "rating";

    /** Longest text the knowledge base is searched with when a follow-up adds the previous question. */
    public static final int FOLLOW_UP_QUERY_MAX_CHARACTERS = 2000;

    public static final String QUERY_PLACEHOLDER = "{query}";
    public static final String CONTEXT_PLACEHOLDER = "{context}";

    public static final String DEFAULT_NO_CONTEXT_MESSAGE =
            "No encontré información sobre ese tema en la base de conocimiento de ShopLab. Puedes reformular tu "
            + "pregunta o contactar con la tienda.";

    private ChatConstants() {
    }
}
