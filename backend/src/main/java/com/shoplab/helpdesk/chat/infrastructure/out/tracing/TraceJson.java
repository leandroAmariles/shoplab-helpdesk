package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import tools.jackson.databind.json.JsonMapper;

/** Writes the structured values of a trace (messages, fragments, usage) as the JSON text Langfuse reads. */
final class TraceJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private TraceJson() {
    }

    static String write(Object value) {
        return MAPPER.writeValueAsString(value);
    }
}
