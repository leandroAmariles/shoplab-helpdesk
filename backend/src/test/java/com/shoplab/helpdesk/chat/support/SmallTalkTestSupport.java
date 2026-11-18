package com.shoplab.helpdesk.chat.support;

import com.shoplab.helpdesk.chat.application.port.out.SmallTalkModelPort;
import com.shoplab.helpdesk.chat.application.usecase.SmallTalk;
import com.shoplab.helpdesk.chat.application.usecase.SmallTalkClassifier;
import com.shoplab.helpdesk.chat.config.SmallTalkProperties;
import reactor.core.publisher.Flux;

/** Builds the small-talk path of the chat for tests that are not about it. */
public final class SmallTalkTestSupport {

    private SmallTalkTestSupport() {
    }

    /** The path enabled, with a model that fails the test if a courtesy ever reaches it. */
    public static SmallTalk smallTalk() {
        return smallTalk(true, message -> Flux.error(new AssertionError("Unexpected small talk: " + message)));
    }

    public static SmallTalk smallTalk(boolean enabled, SmallTalkModelPort model) {
        SmallTalkProperties properties = properties(enabled);
        return new SmallTalk(new SmallTalkClassifier(properties), properties, model);
    }

    /** The built-in lexicon, prompt and topics. */
    public static SmallTalkProperties properties(boolean enabled) {
        return new SmallTalkProperties(enabled, null, null, null, null, null, null, null);
    }

    /** A classifier with the built-in lexicon. */
    public static SmallTalkClassifier classifier() {
        return new SmallTalkClassifier(properties(true));
    }
}
