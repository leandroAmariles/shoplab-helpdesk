package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.retrieval.api.KnowledgeChunk;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Turns a chunk published by {@code retrieval} into the fragment {@code chat} hands to the model. */
@Component
public class ContextFragmentMapper {

    public ContextFragment toFragment(KnowledgeChunk chunk) {
        return new ContextFragment(chunk.source(), titleOf(chunk), chunk.section() == null ? "" : chunk.section(),
                chunk.position(), chunk.text(), chunk.score());
    }

    /** The customer reads the title, not the file name: a missing one is derived from the name as a last resort. */
    private static String titleOf(KnowledgeChunk chunk) {
        if (chunk.title() != null && !chunk.title().isBlank()) {
            return chunk.title().strip();
        }
        String name = chunk.source();
        int extension = name.lastIndexOf('.');
        String words = (extension > 0 ? name.substring(0, extension) : name).replaceAll("[-_]+", " ").strip();
        return words.isEmpty() ? name : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }
}
