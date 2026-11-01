package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import org.springframework.stereotype.Component;

/** Turns a chunk published by {@code retrieval} into the fragment {@code chat} hands to the model. */
@Component
public class ContextFragmentMapper {

    public ContextFragment toFragment(KnowledgeChunk chunk) {
        return new ContextFragment(chunk.source(), chunk.section() == null ? "" : chunk.section(),
                chunk.position(), chunk.text(), chunk.score());
    }
}
