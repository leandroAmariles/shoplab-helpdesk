package com.shoplab.helpdesk.chat.domain.model;

/**
 * A piece of the knowledge base handed to the model as context for one answer.
 *
 * @param source   file name of the document it comes from
 * @param title    readable title of that document
 * @param section  path of headings that contains it, empty when there is none
 * @param position position of the chunk in the document
 * @param text     text of the chunk
 * @param score    similarity with the question; the higher, the closer
 */
public record ContextFragment(String source, String title, String section, int position, String text, double score) {

    public ContextFragment {
        if (source == null || section == null) {
            throw new IllegalArgumentException("A context fragment needs a source and a section (maybe empty)");
        }
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("A context fragment needs the title of its document");
        }
        if (position < 0) {
            throw new IllegalArgumentException("The position of a context fragment must not be negative");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("A context fragment needs text");
        }
    }
}
