package com.shoplab.helpdesk.chat.application.port.out;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import java.util.List;
import reactor.core.publisher.Flux;

public interface ChatModelPort {

    /**
     * Text fragments of the model's answer, in generation order. The history is what was said before in the
     * conversation, oldest first, handed over as earlier messages (never as instructions); the context is what the
     * answer may rely on, and empty means the store knows nothing about the question. Cancelling the subscription
     * stops the model.
     */
    Flux<String> streamAnswer(String message, List<ConversationTurn> history, List<ContextFragment> context);
}
