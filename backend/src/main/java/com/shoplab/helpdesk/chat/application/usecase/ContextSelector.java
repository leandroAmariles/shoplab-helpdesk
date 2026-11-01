package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Decides which of the candidate fragments reach the model: the ones similar enough, without repeated text, and as
 * many as fit in the character budget. Fragments go in whole or not at all, because cutting one in half changes what
 * it says.
 */
@Component
public class ContextSelector {

    private static final Logger LOG = LoggerFactory.getLogger(ContextSelector.class);

    /**
     * @param candidates    fragments from the search, closest first
     * @param threshold     lowest similarity kept; 0 or less keeps every fragment
     * @param maxCharacters most characters of text handed over in total
     */
    public List<ContextFragment> select(List<ContextFragment> candidates, double threshold, int maxCharacters) {
        List<ContextFragment> unique = withoutRepeatedText(candidates.stream()
                .filter(fragment -> threshold <= 0 || fragment.score() >= threshold)
                .toList());
        List<ContextFragment> selected = new ArrayList<>();
        int used = 0;
        for (ContextFragment fragment : unique) {
            int length = fragment.text().length();
            if (used + length > maxCharacters) {
                if (selected.isEmpty()) {
                    LOG.warn("The closest fragment exceeds the context budget and is handed over alone: "
                            + "length={}, budget={}", length, maxCharacters);
                    selected.add(fragment);
                }
                break;
            }
            selected.add(fragment);
            used += length;
        }
        return List.copyOf(selected);
    }

    /** The same text twice (a re-ingested document, copied policies) is shown once, with the better score. */
    private List<ContextFragment> withoutRepeatedText(List<ContextFragment> fragments) {
        Map<String, ContextFragment> byText = new LinkedHashMap<>();
        for (ContextFragment fragment : fragments) {
            byText.merge(normalize(fragment.text()), fragment,
                    (kept, other) -> other.score() > kept.score() ? other : kept);
        }
        return List.copyOf(byText.values());
    }

    private String normalize(String text) {
        return text.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
