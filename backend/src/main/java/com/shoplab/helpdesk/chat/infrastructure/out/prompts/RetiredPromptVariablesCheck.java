package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;

/**
 * The text of the prompts moved to Langfuse (LAB-85): the environment variables that used to carry it are no longer
 * read. An environment that still defines one would be silently ignored, so this says it once at start-up, naming the
 * variable and where the prompt is edited now. It never prints the value.
 */
public final class RetiredPromptVariablesCheck {

    private static final Logger LOG = LoggerFactory.getLogger(RetiredPromptVariablesCheck.class);
    private static final List<String> RETIRED = List.of(PromptConstants.RETIRED_SYSTEM_PROMPT_VARIABLE,
            PromptConstants.RETIRED_SMALL_TALK_PROMPT_VARIABLE);

    private final Environment environment;

    public RetiredPromptVariablesCheck(Environment environment) {
        this.environment = environment;
    }

    /** @return the retired variables the environment still defines */
    public List<String> warn() {
        List<String> defined = RETIRED.stream().filter(environment::containsProperty).toList();
        for (String variable : defined) {
            LOG.warn("The variable {} is no longer read: the text of the prompts is edited in Langfuse (prompts "
                    + "chat-system, chat-small-talk, chat-context and chat-empty-context). Remove it from the "
                    + "environment", variable);
        }
        return defined;
    }
}
