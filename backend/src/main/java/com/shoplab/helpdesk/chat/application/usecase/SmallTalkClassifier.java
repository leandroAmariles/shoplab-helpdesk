package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.config.SmallTalkProperties;
import com.shoplab.helpdesk.chat.domain.model.constants.SmallTalkConstants;
import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Tells whether a message is only a courtesy: a greeting, thanks, a farewell, a short acknowledgment or "what can you
 * do?" (LAB-114). It is deterministic and calls no model and no search, so it can run before anything else. A message
 * is a courtesy only if <strong>all</strong> of it splits into phrases of the closed lexicon of
 * {@link SmallTalkProperties} (built-in lists unless the configuration replaces them); anything else (a question, a
 * request, a number, an unknown word, only emojis) is a question and follows the grounded flow. When in doubt the
 * answer is "not a courtesy".
 */
@Component
public class SmallTalkClassifier {

    private static final String PROPERTY_PREFIX = "shoplab.chat.small-talk.";
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NOT_LETTER_DIGIT_OR_SPACE = Pattern.compile("[^\\p{L}\\p{N}\\s]");
    private static final Pattern REPEATED_LETTER = Pattern.compile("(\\p{L})\\1{2,}");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");

    private final Set<String> phrases;
    private final int maxPhraseTokens;

    /**
     * @throws IllegalArgumentException if a configured phrase is empty once normalized, has a digit or has more words
     *                                  than a whole message may have, naming the property
     */
    public SmallTalkClassifier(SmallTalkProperties properties) {
        Set<String> lexicon = new HashSet<>();
        add(lexicon, "greetings", properties.greetings());
        add(lexicon, "thanks", properties.thanks());
        add(lexicon, "farewells", properties.farewells());
        add(lexicon, "acknowledgments", properties.acknowledgments());
        add(lexicon, "capabilities", properties.capabilities());
        this.phrases = Set.copyOf(lexicon);
        this.maxPhraseTokens = lexicon.stream().mapToInt(phrase -> phrase.split(" ").length).max().orElse(1);
    }

    private static void add(Set<String> lexicon, String property, List<String> configured) {
        for (String phrase : configured) {
            String normalized = normalize(phrase);
            if (normalized.isEmpty() || DIGIT.matcher(normalized).find()
                    || normalized.split(" ").length > SmallTalkConstants.MAX_TOKENS) {
                throw new IllegalArgumentException(PROPERTY_PREFIX + property + " has the phrase '" + phrase
                        + "', which is empty, has a digit or has more than " + SmallTalkConstants.MAX_TOKENS
                        + " words");
            }
            lexicon.add(normalized);
        }
    }

    /** Whether the whole message is made of courtesies. {@code null}, blank and over-long messages are not. */
    public boolean isSmallTalk(String message) {
        if (message == null || message.isBlank() || message.length() > SmallTalkConstants.MAX_MESSAGE_CHARACTERS) {
            return false;
        }
        String normalized = normalize(message);
        if (normalized.isEmpty() || DIGIT.matcher(normalized).find()) {
            return false;
        }
        String[] tokens = normalized.split(" ");
        return tokens.length <= SmallTalkConstants.MAX_TOKENS && coveredByPhrases(tokens);
    }

    /** Words of the longest phrase of the lexicon in use: the cap of the segmentation. */
    int maxPhraseTokens() {
        return maxPhraseTokens;
    }

    /**
     * Lower case, no accents, no signs or emojis, a letter repeated three times or more reduced to one ("holaaa"),
     * and single spaces. The same text always gives the same result.
     */
    static String normalize(String message) {
        String decomposed = Normalizer.normalize(message, Normalizer.Form.NFD);
        String plain = DIACRITICS.matcher(decomposed).replaceAll("").toLowerCase(Locale.ROOT);
        String words = NOT_LETTER_DIGIT_OR_SPACE.matcher(plain).replaceAll(" ");
        String shortened = REPEATED_LETTER.matcher(words).replaceAll("$1");
        return SPACES.matcher(shortened).replaceAll(" ").trim();
    }

    /** Whether the tokens split completely into lexicon phrases; {@code covered[i]} is true if tokens from i do. */
    private boolean coveredByPhrases(String[] tokens) {
        boolean[] covered = new boolean[tokens.length + 1];
        covered[tokens.length] = true;
        for (int start = tokens.length - 1; start >= 0; start--) {
            int longest = Math.min(maxPhraseTokens, tokens.length - start);
            for (int length = 1; length <= longest && !covered[start]; length++) {
                covered[start] = covered[start + length]
                        && phrases.contains(String.join(" ", Arrays.copyOfRange(tokens, start, start + length)));
            }
        }
        return covered[0];
    }
}
