package com.shoplab.helpdesk.chat.infrastructure.out.tracing;

import io.opentelemetry.api.common.AttributeKey;
import java.util.List;

/**
 * Names of the span attributes Langfuse understands (specs/LAB-64, contracts/trace-schema.md). The {@code langfuse.*}
 * ones win over the generic {@code gen_ai.*} conventions.
 */
final class LangfuseAttributes {

    static final String TURN_SPAN = "chat-turn";
    static final String RETRIEVAL_SPAN = "retrieval";
    static final String GENERATION_SPAN = "generation";
    static final String TOOL_SPAN_PREFIX = "tool:";

    static final String TYPE_SPAN = "span";
    static final String TYPE_RETRIEVER = "retriever";
    static final String TYPE_GENERATION = "generation";
    static final String TYPE_TOOL = "tool";

    static final String LEVEL_DEFAULT = "DEFAULT";
    static final String LEVEL_WARNING = "WARNING";
    static final String LEVEL_ERROR = "ERROR";

    static final AttributeKey<String> TRACE_NAME = AttributeKey.stringKey("langfuse.trace.name");
    static final AttributeKey<String> TRACE_INPUT = AttributeKey.stringKey("langfuse.trace.input");
    static final AttributeKey<String> TRACE_OUTPUT = AttributeKey.stringKey("langfuse.trace.output");
    static final AttributeKey<List<String>> TRACE_TAGS = AttributeKey.stringArrayKey("langfuse.trace.tags");
    static final AttributeKey<String> SESSION_ID = AttributeKey.stringKey("langfuse.session.id");
    static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey("langfuse.environment");

    static final AttributeKey<String> OBSERVATION_TYPE = AttributeKey.stringKey("langfuse.observation.type");
    static final AttributeKey<String> OBSERVATION_INPUT = AttributeKey.stringKey("langfuse.observation.input");
    static final AttributeKey<String> OBSERVATION_OUTPUT = AttributeKey.stringKey("langfuse.observation.output");
    static final AttributeKey<String> OBSERVATION_LEVEL = AttributeKey.stringKey("langfuse.observation.level");
    static final AttributeKey<String> OBSERVATION_STATUS_MESSAGE =
            AttributeKey.stringKey("langfuse.observation.status_message");
    static final AttributeKey<String> MODEL_NAME = AttributeKey.stringKey("langfuse.observation.model.name");
    static final AttributeKey<String> MODEL_PARAMETERS =
            AttributeKey.stringKey("langfuse.observation.model.parameters");
    static final AttributeKey<String> USAGE_DETAILS = AttributeKey.stringKey("langfuse.observation.usage_details");
    static final AttributeKey<String> COST_DETAILS = AttributeKey.stringKey("langfuse.observation.cost_details");
    static final AttributeKey<String> COMPLETION_START_TIME =
            AttributeKey.stringKey("langfuse.observation.completion_start_time");

    private static final String TRACE_METADATA = "langfuse.trace.metadata.";
    private static final String OBSERVATION_METADATA = "langfuse.observation.metadata.";

    private LangfuseAttributes() {
    }

    static AttributeKey<String> traceMetadata(String name) {
        return AttributeKey.stringKey(TRACE_METADATA + name);
    }

    static AttributeKey<String> observationMetadata(String name) {
        return AttributeKey.stringKey(OBSERVATION_METADATA + name);
    }
}
