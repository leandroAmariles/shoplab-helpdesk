package com.shoplab.helpdesk.architecture.violations.domain;

import org.springframework.ai.chat.messages.UserMessage;

/** Fixture: a domain class that knows Spring AI (violates D1). */
public class SpringAiInDomain {

    public UserMessage message(String text) {
        return new UserMessage(text);
    }
}
