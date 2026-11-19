package com.shoplab.helpdesk.chat.infrastructure.out.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;

class DelimiterEchoFilterTests {

    private static String strip(String... fragments) {
        List<String> parts = DelimiterEchoFilter.strip(Flux.just(fragments)).collectList().block();
        return String.join("", parts);
    }

    @Test
    void removesTheDelimiterThatWrapsTheWholeReply() {
        assertThat(strip("<mensaje_del_cliente> hola, ¿en qué puedo ayudarte hoy? </mensaje_del_cliente>"))
                .isEqualTo(" hola, ¿en qué puedo ayudarte hoy? ");
    }

    @Test
    void removesTheDelimiterWhenItArrivesSplitInAnyPlace() {
        assertThat(strip("<", "mensaje_", "del_cli", "ente", ">", "Hola", "</mensaje", "_del_cliente", ">"))
                .isEqualTo("Hola");
    }

    @ParameterizedTest
    @ValueSource(strings = {"<mensaje_del_cliente>", "</mensaje_del_cliente>", "< / MENSAJE_DEL_CLIENTE >",
            "<Mensaje_Del_Cliente >"})
    void removesTheTagInAnyCaseAndSpacing(String tag) {
        assertThat(strip("a", tag, "b")).isEqualTo("ab");
    }

    @Test
    void leavesTheReplyUntouchedWhenThereIsNoDelimiter() {
        assertThat(strip("Hola", " señor", " 😀", ", ¿en", " qué puedo ayudarle?"))
                .isEqualTo("Hola señor 😀, ¿en qué puedo ayudarle?");
    }

    @Test
    void keepsOtherAngleBracketsAndReleasesAHeldTailThatNeverBecameATag() {
        assertThat(strip("2 < 3 y ", "<mensaje_del")).isEqualTo("2 < 3 y <mensaje_del");
        assertThat(strip("usa <b>negrita</b>", " y <")).isEqualTo("usa <b>negrita</b> y <");
    }

    @Test
    void holdsBackOnlyWhatCouldStillBeATag() {
        List<String> emitted = DelimiterEchoFilter.strip(Flux.just("Hola <", "mensaje_", "otra cosa")).collectList()
                .block();

        assertThat(emitted).containsExactly("Hola ", "<mensaje_otra cosa");
    }

    @Test
    void everySubscriptionStartsClean() {
        Flux<String> stripped = DelimiterEchoFilter.strip(Flux.just("<mensaje_", "del_cliente>", "ok", "<men"));

        assertThat(String.join("", stripped.collectList().block())).isEqualTo("ok<men");
        assertThat(String.join("", stripped.collectList().block())).isEqualTo("ok<men");
    }
}
