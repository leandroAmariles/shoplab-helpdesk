package com.shoplab.helpdesk.chat.api;

/**
 * What the chat module publishes about how the assistant is set up right now, so the evaluation can record what a run
 * was measured with (specs/LAB-31). It only reads memory: no network and no waiting.
 */
public interface AssistantSettingsApi {

    AssistantSettings current();
}
