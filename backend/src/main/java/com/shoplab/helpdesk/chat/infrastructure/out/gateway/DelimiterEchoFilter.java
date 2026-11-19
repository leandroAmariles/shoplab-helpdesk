package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Small models sometimes echo the delimiter that wraps the customer's message and answer inside it:
 * {@code <mensaje_del_cliente> hola </mensaje_del_cliente>}. The delimiter is ours and means nothing to the customer,
 * so it is removed from the reply before it reaches the stream, the trace or the citations.
 *
 * <p>The model streams its answer in arbitrary pieces, so a tag can arrive split
 * ({@code <mensaje_} + {@code del_cliente>}). The filter holds back the tail of a fragment only while it could still
 * become a tag, and releases it as soon as it cannot (or when the stream completes). One instance serves one stream.
 */
final class DelimiterEchoFilter {

    private static final Pattern TAG = Pattern.compile(
            "<\\s*/?\\s*mensaje_del_cliente\\s*>", Pattern.CASE_INSENSITIVE);

    private String held = "";

    /** The reply without the delimiters. Cold: every subscription gets its own filter. */
    static Flux<String> strip(Flux<String> fragments) {
        return Flux.defer(() -> {
            DelimiterEchoFilter filter = new DelimiterEchoFilter();
            return fragments.map(filter::accept)
                    .filter(text -> !text.isEmpty())
                    .concatWith(Mono.fromSupplier(filter::release).filter(text -> !text.isEmpty()));
        });
    }

    /** What can be emitted now; a possible start of a tag stays held for the next fragment. */
    private String accept(String fragment) {
        String text = TAG.matcher(held + fragment).replaceAll("");
        int cut = startOfPossibleTag(text);
        held = text.substring(cut);
        return text.substring(0, cut);
    }

    /** What was held when the stream ended: it never became a tag, so it is part of the reply. */
    private String release() {
        String rest = held;
        held = "";
        return rest;
    }

    /** Index where a suffix of the text begins that would be a tag with more input; the length if there is none. */
    private static int startOfPossibleTag(String text) {
        int open = text.lastIndexOf('<');
        if (open < 0) {
            return text.length();
        }
        Matcher tail = TAG.matcher(text).region(open, text.length());
        return !tail.lookingAt() && tail.hitEnd() ? open : text.length();
    }
}
