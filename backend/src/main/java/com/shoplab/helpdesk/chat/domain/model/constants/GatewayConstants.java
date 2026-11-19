package com.shoplab.helpdesk.chat.domain.model.constants;

/** What the chat module knows about the model gateway (LAB-84); nothing here depends on the gateway's product. */
public final class GatewayConstants {

    /** The logical model name the backend asks for; the gateway maps it to a real model (FR-006). */
    public static final String CHAT_MODEL = "chat";

    /** Key under which the id of the conversation travels in the Reactor context, to the model adapter. */
    public static final String CONVERSATION_ID_CONTEXT_KEY = "shoplab.chat.conversationId";

    /** Type of the gateway's error body when a virtual key has spent its budget. */
    public static final String BUDGET_EXCEEDED_ERROR_TYPE = "budget_exceeded";

    private GatewayConstants() {
    }
}
