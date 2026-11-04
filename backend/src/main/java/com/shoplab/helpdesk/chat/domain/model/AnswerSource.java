package com.shoplab.helpdesk.chat.domain.model;

/**
 * A fragment the answer was generated from, as the customer sees it: what a citation {@code [number]} points at.
 *
 * @param number  position of the fragment in the context handed to the model, starting at 1
 * @param title   readable title of the document
 * @param section path of headings that contains the fragment, empty when there is none
 * @param text    text of the fragment as it was handed to the model
 */
public record AnswerSource(int number, String title, String section, String text) {

    public AnswerSource {
        if (number < 1) {
            throw new IllegalArgumentException("The number of a source starts at 1");
        }
        if (title == null || title.isBlank() || text == null || text.isBlank()) {
            throw new IllegalArgumentException("A source needs a title and a text");
        }
        if (section == null) {
            throw new IllegalArgumentException("The section of a source may be empty but not null");
        }
    }

    /** The source for the fragment at {@code index} (0-based) of the context. */
    public static AnswerSource of(int index, ContextFragment fragment) {
        return new AnswerSource(index + 1, fragment.title(), fragment.section(), fragment.text());
    }
}
