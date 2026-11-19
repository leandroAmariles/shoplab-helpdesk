package com.shoplab.helpdesk.chat.domain.model.constants;

public final class TracingConstants {

    /** Key under which the trace of the turn travels in the Reactor context, from the use case to the model adapter. */
    public static final String TURN_TRACE_KEY = "shoplab.chat.turnTrace";

    public static final String USAGE_KNOWN = "known";
    public static final String USAGE_UNKNOWN = "unknown";

    public static final String COST_PRICED = "priced";
    public static final String COST_UNPRICED = "unpriced";
    public static final String COST_UNKNOWN_USAGE = "unknown_usage";

    /** Added at the end of a text that was cut to the limit. */
    public static final String TRUNCATED_SUFFIX = "…[truncated]";

    private TracingConstants() {
    }
}
