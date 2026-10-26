package com.shoplab.helpdesk.chat.application.port.out;

import reactor.core.publisher.Flux;

public interface ChatModelPort {

    /** Text fragments of the model's answer, in generation order. Cancelling the subscription stops the model. */
    Flux<String> streamAnswer(String message);
}
