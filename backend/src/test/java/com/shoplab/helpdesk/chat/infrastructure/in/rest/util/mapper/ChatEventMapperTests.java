package com.shoplab.helpdesk.chat.infrastructure.in.rest.util.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import com.shoplab.helpdesk.chat.domain.model.AnswerSource;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.SourcesEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.infrastructure.in.rest.dto.ChatEventPayload;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.codec.ServerSentEvent;

class ChatEventMapperTests {

    @Test
    void theSourcesBecomeASourcesEventWithEveryFieldOfEachSource() {
        SourcesEvent event = new SourcesEvent(List.of(
                new AnswerSource(1, "Política de devoluciones", "Plazos", "Tienes 30 días."),
                new AnswerSource(2, "Reembolsos", "", "Diez días.")));

        ServerSentEvent<Object> sse = ChatEventMapper.toServerSentEvent(event);

        assertThat(sse.event()).isEqualTo("sources");
        assertThat(sse.data()).isEqualTo(new ChatEventPayload.Sources(List.of(
                new ChatEventPayload.Source(1, "Política de devoluciones", "Plazos", "Tienes 30 días."),
                new ChatEventPayload.Source(2, "Reembolsos", "", "Diez días."))));
    }

    @Test
    void theOtherEventsKeepTheirNameAndShape() {
        assertThat(ChatEventMapper.toServerSentEvent(new TokenEvent("hola")))
                .extracting(ServerSentEvent::event, ServerSentEvent::data)
                .containsExactly("token", new ChatEventPayload.Token("hola"));
        assertThat(ChatEventMapper.toServerSentEvent(new DoneEvent(3)))
                .extracting(ServerSentEvent::event, ServerSentEvent::data)
                .containsExactly("done", new ChatEventPayload.Done(3));
        assertThat(ChatEventMapper.toServerSentEvent(new ErrorEvent("SLHD_CHAT_00003", "boom")))
                .extracting(ServerSentEvent::event, ServerSentEvent::data)
                .containsExactly("error", new ChatEventPayload.Error("SLHD_CHAT_00003", "boom"));
    }

    @Test
    void aSourceNeedsANumberFromOneATitleAndATextAndASourcesEventNeedsSources() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnswerSource(0, "t", "", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnswerSource(1, " ", "", "x"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnswerSource(1, "t", null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new AnswerSource(1, "t", "", ""))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new SourcesEvent(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
