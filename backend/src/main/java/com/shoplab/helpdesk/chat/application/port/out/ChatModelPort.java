package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.util.List;
import reactor.core.publisher.Flux;

public interface ChatModelPort {

    /**
     * Text fragments of the model's answer, in generation order. The context is what the answer may rely on; empty
     * means the store knows nothing about the question. Cancelling the subscription stops the model.
     */
    Flux<String> streamAnswer(String message, List<ContextFragment> context);
}
