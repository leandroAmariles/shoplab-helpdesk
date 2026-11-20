package com.shoplab.helpdesk.chat.domain.model.constants;

public final class PromptConstants {

    /** Label that tells Langfuse which version of each prompt the chat serves. */
    public static final String DEFAULT_LABEL = "production";

    /** Request headers that fix prompt versions; only the evaluation uses them (LAB-85). */
    public static final String VERSIONS_HEADER = "X-Prompt-Versions";
    public static final String TOKEN_HEADER = "X-Prompt-Token";

    /** Key under which the versions fixed by the request travel in the Reactor context, down to the model adapter. */
    public static final String OVERRIDES_CONTEXT_KEY = "shoplab.chat.promptOverrides";

    /** Versions fixed by the evaluation that are kept in memory (a version never changes, so they do not expire). */
    public static final int PINNED_CACHE_SIZE = 32;

    /** What a trace says about a prompt whose text did not come from Langfuse. */
    public static final String DEFAULT_TRACE_VALUE = "default";

    public static final String REFRESH_METRIC = "shoplab.chat.prompts.refresh";
    public static final String LOADED_VERSION_METRIC = "shoplab.chat.prompts.version";
    public static final String PROMPT_TAG = "prompt";
    public static final String RESULT_TAG = "result";

    /** Results of one refresh of one prompt (value of {@link #RESULT_TAG}). */
    public static final String RESULT_UPDATED = "updated";
    public static final String RESULT_UNCHANGED = "unchanged";
    public static final String RESULT_INVALID = "invalid";
    public static final String RESULT_MISSING = "missing";
    public static final String RESULT_FAILED = "failed";

    /** Reasons that are not a validation failure: the prompt does not exist, or Langfuse did not answer. */
    public static final String REASON_MISSING = "missing";
    public static final String REASON_FAILED = "failed";

    /** Environment variables whose prompt text moved to Langfuse (LAB-85); their values are no longer read. */
    public static final String RETIRED_SYSTEM_PROMPT_VARIABLE = "SHOPLAB_CHAT_SYSTEM_PROMPT";
    public static final String RETIRED_SMALL_TALK_PROMPT_VARIABLE = "SHOPLAB_CHAT_SMALL_TALK_SYSTEM_PROMPT";

    private PromptConstants() {
    }
}
