package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.domain.model.enums.CitationOutcome;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Counts the citations {@code [n]} of one answer while it streams, to tell whether the model cites the sources it was
 * given. It only observes: the tokens travel to the customer untouched, and the client decides what to show. The text
 * is kept just to find markers that arrive split between tokens, never logged and never exposed.
 *
 * <p>The grammar is shared with the client (specs/LAB-23-source-citations/contracts/sources-event.md §3): a marker is
 * {@code [} one or more numbers of 1 or 2 digits or ranges {@code a-b}, separated by commas, {@code ]}.
 */
public final class CitationTracker {

    private static final Pattern MARKER = Pattern.compile(
            "\\[\\s*(\\d{1,2}(?:\\s*-\\s*\\d{1,2})?(?:\\s*,\\s*\\d{1,2}(?:\\s*-\\s*\\d{1,2})?)*)\\s*]");
    private static final int MAX_NUMBERS_PER_MARKER = 20;

    private final StringBuilder text = new StringBuilder();

    /** Citations of an answer by validity; deliberately without any text. */
    public record Summary(int valid, int invalid) {

        public CitationOutcome outcome() {
            if (invalid > 0) {
                return CitationOutcome.INVALID;
            }
            return valid > 0 ? CitationOutcome.VALID : CitationOutcome.NONE;
        }
    }

    public void append(String token) {
        text.append(token);
    }

    public boolean isEmpty() {
        return text.isEmpty();
    }

    /** Counts every cited number: valid when it is between 1 and {@code sourceCount}, invalid otherwise. */
    public Summary summarize(int sourceCount) {
        int valid = 0;
        int invalid = 0;
        Matcher marker = MARKER.matcher(text);
        while (marker.find()) {
            int[] range = expand(marker.group(1));
            if (range == null) {
                continue;
            }
            for (int number : range) {
                if (number <= sourceCount) {
                    valid++;
                } else {
                    invalid++;
                }
            }
        }
        return new Summary(valid, invalid);
    }

    /** Expands {@code 1, 3-4} into {@code [1, 3, 4]}; {@code null} when a number is 0 or a range is invalid. */
    private static int[] expand(String inner) {
        int[] numbers = new int[MAX_NUMBERS_PER_MARKER];
        int count = 0;
        for (String part : inner.split(",")) {
            String[] bounds = part.split("-");
            int from = Integer.parseInt(bounds[0].strip());
            int to = Integer.parseInt(bounds[bounds.length - 1].strip());
            if (from < 1 || to < from || to - from + 1 > MAX_NUMBERS_PER_MARKER
                    || count + (to - from + 1) > MAX_NUMBERS_PER_MARKER) {
                return null;
            }
            for (int number = from; number <= to; number++) {
                numbers[count++] = number;
            }
        }
        return Arrays.copyOf(numbers, count);
    }
}
