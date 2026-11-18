package com.shoplab.helpdesk.chat.application.port.out;

import reactor.core.publisher.Flux;

/**
 * The model that answers a courtesy (LAB-114): a greeting, thanks, a farewell or "what can you do?". Unlike
 * {@link ChatModelPort} it receives no history, no context fragments and no tools: the only thing the customer
 * contributes is a message made of closed-vocabulary courtesies.
 */
public interface SmallTalkModelPort {

    /**
     * The answer to a courtesy, in the order the model generates it. Cancelling the subscription stops the model; any
     * failure is a typed {@code ChatModelException}, as with {@link ChatModelPort}.
     *
     * @param message the courtesy the classifier accepted
     */
    Flux<String> streamReply(String message);
}
