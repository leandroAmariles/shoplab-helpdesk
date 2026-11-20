package com.shoplab.helpdesk.chat.config;

import com.shoplab.helpdesk.chat.domain.model.constants.SmallTalkConstants;
import java.util.Collection;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Settings of the small-talk path under {@code shoplab.chat.small-talk} (LAB-114). The instructions of its answer are
 * the {@code chat-small-talk} prompt (LAB-85), not a setting.
 *
 * <p>The five lists are the closed lexicon of courtesies: a message is small talk only if all of it splits into phrases
 * of these lists. Each list that is empty keeps the built-in one of {@link SmallTalkConstants}; one that is set replaces
 * it. Case, accents and signs of the phrases do not matter (they are normalized like the messages). Widening a list
 * widens what skips the search and the grounded answer: keep it to courtesies and check it with the golden dataset.
 *
 * @param enabled         whether courtesies are answered without retrieval (default {@code true}); {@code false}
 *                        restores the behavior before LAB-114
 * @param topics          what the assistant says it can help with; blank keeps the default
 * @param greetings       phrases that greet ("hola", "buenas tardes")
 * @param thanks          phrases that thank ("gracias", "thank you")
 * @param farewells       phrases that say goodbye ("adiós", "hasta luego")
 * @param acknowledgments short acknowledgments ("vale", "perfecto")
 * @param capabilities    ways of asking what the assistant can do ("¿qué puedes hacer?", "who are you")
 */
@ConfigurationProperties("shoplab.chat.small-talk")
public record SmallTalkProperties(Boolean enabled, String topics, List<String> greetings, List<String> thanks,
        List<String> farewells, List<String> acknowledgments, List<String> capabilities) {

    public SmallTalkProperties {
        enabled = enabled == null || enabled;
        topics = topics == null || topics.isBlank() ? SmallTalkConstants.DEFAULT_TOPICS : topics.trim();
        greetings = phrasesOrDefault(greetings, SmallTalkConstants.GREETINGS);
        thanks = phrasesOrDefault(thanks, SmallTalkConstants.THANKS);
        farewells = phrasesOrDefault(farewells, SmallTalkConstants.FAREWELLS);
        acknowledgments = phrasesOrDefault(acknowledgments, SmallTalkConstants.ACKNOWLEDGMENTS);
        capabilities = phrasesOrDefault(capabilities, SmallTalkConstants.CAPABILITIES);
    }

    /** The configured phrases without blank entries (an empty value in an environment variable), or the defaults. */
    private static List<String> phrasesOrDefault(List<String> configured, Collection<String> defaults) {
        List<String> phrases = configured == null ? List.of()
                : configured.stream().filter(phrase -> phrase != null && !phrase.isBlank()).map(String::trim).toList();
        return phrases.isEmpty() ? defaults.stream().sorted().toList() : phrases;
    }
}
