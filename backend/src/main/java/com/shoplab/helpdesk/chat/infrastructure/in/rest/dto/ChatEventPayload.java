package com.shoplab.helpdesk.chat.infrastructure.in.rest.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** JSON carried in the {@code data} line of each server-sent event (specs/LAB-12/contracts/chat-stream.md). */
public final class ChatEventPayload {

    private ChatEventPayload() {
    }

    public record Sources(List<Source> sources) {
    }

    public record Source(int number, String title, String section, String text) {
    }

    public record Token(String content) {
    }

    public record Done(int fragments, @JsonInclude(JsonInclude.Include.NON_NULL) String turnId) {

        public Done(int fragments) {
            this(fragments, null);
        }
    }

    public record Error(String code, String message) {
    }
}
