package com.shoplab.helpdesk.shared.constants;

/**
 * What the backend sends to the model gateway so the gateway's logs, the backend's logs and the traces can be crossed
 * (LAB-84, FR-025). It lives in {@code shared} because both {@code chat} and {@code retrieval} call the gateway and
 * modules must not reach into each other.
 */
public final class GatewayHeaders {

    /** The id of the chat request ({@code X-Request-Id}), as a header of every call to the gateway. */
    public static final String REQUEST_ID_HEADER = "x-shoplab-request-id";

    /** Key under which the request id travels in the Reactor context, from the use case to the model adapters. */
    public static final String REQUEST_ID_CONTEXT_KEY = "shoplab.gateway.requestId";

    private GatewayHeaders() {
    }
}
