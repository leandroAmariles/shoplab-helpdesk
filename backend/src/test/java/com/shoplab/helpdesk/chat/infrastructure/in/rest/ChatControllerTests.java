package com.shoplab.helpdesk.chat.infrastructure.in.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.shoplab.helpdesk.chat.application.port.in.StreamChatServicePort;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import com.shoplab.helpdesk.shared.infrastructure.in.rest.filter.RequestIdFilter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import reactor.core.publisher.Flux;

@WebMvcTest(ChatController.class)
class ChatControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StreamChatServicePort streamChat;

    @Test
    void streamsTokensAndDoneAsServerSentEvents() throws Exception {
        when(streamChat.stream(any())).thenReturn(Flux.just(new TokenEvent("Hola"), new TokenEvent(" mundo"),
                new DoneEvent(2)));

        MockHttpServletResponse response = perform(chatRequest("{\"message\":\"Hola\"}"));

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE);
        assertThat(response.getContentAsString())
                .contains("event:token", "data:{\"content\":\"Hola\"}", "data:{\"content\":\" mundo\"}")
                .contains("event:done", "data:{\"fragments\":2}");
        assertThat(response.getContentAsString().indexOf("event:done"))
                .isGreaterThan(response.getContentAsString().lastIndexOf("event:token"));
    }

    @Test
    void disablesCachingAndProxyBufferingOfTheStream() throws Exception {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        MockHttpServletResponse response = perform(chatRequest("{\"message\":\"Hola\"}"));

        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-cache");
        assertThat(response.getHeader("X-Accel-Buffering")).isEqualTo("no");
    }

    @Test
    void buildsTheCommandWithTheMessageAndTheRequestId() throws Exception {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        perform(chatRequest("{\"message\":\"¿Dónde está mi pedido?\"}").header("X-Request-Id", "req-42"));

        ArgumentCaptor<StreamChatCommand> captor = ArgumentCaptor.forClass(StreamChatCommand.class);
        verify(streamChat).stream(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new StreamChatCommand("¿Dónde está mi pedido?", "req-42"));
    }

    @Test
    void generatesARequestIdWhenTheClientSendsNone() throws Exception {
        when(streamChat.stream(any())).thenReturn(Flux.just(new DoneEvent(0)));

        MockHttpServletResponse response = perform(chatRequest("{\"message\":\"Hola\"}"));

        ArgumentCaptor<StreamChatCommand> captor = ArgumentCaptor.forClass(StreamChatCommand.class);
        verify(streamChat).stream(captor.capture());
        assertThat(captor.getValue().requestId()).isNotBlank()
                .isEqualTo(response.getHeader(RequestIdFilter.HEADER));
    }

    @Test
    void aBusinessErrorBeforeTheStreamStartsIsABadRequestWithJson() throws Exception {
        when(streamChat.stream(any())).thenReturn(
                Flux.error(ExceptionUtil.business(ResponseCodeEnum.INVALID_CHAT_MESSAGE)));

        MockHttpServletResponse response = perform(chatRequest("{\"message\":\"   \"}"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"SLHD_CHAT_00001\"");
    }

    @Test
    void aFailureBeforeTheFirstEventIsServiceUnavailableEvenForAnEventStreamClient() throws Exception {
        when(streamChat.stream(any())).thenReturn(Flux.error(
                ExceptionUtil.technical(ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE, new IllegalStateException("down"))));

        MockHttpServletResponse response = perform(chatRequest("{\"message\":\"Hola\"}"));

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(response.getContentAsString()).contains("\"code\":\"SLHD_CHAT_00002\"");
    }

    @Test
    void anUnreadableBodyIsABadRequestWithTheGenericCode() throws Exception {
        MockHttpServletResponse response = perform(chatRequest("{\"message\":"));

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":\"SLHD_GENE_00002\"");
    }

    private MockHttpServletRequestBuilder chatRequest(String json) {
        return post("/api/chat").contentType(MediaType.APPLICATION_JSON).accept(MediaType.TEXT_EVENT_STREAM)
                .content(json);
    }

    /** Performs the call and, when the stream went async, follows the async dispatch to get the whole body. */
    private MockHttpServletResponse perform(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result.getAsyncResult();
            return mockMvc.perform(asyncDispatch(result)).andReturn().getResponse();
        }
        return result.getResponse();
    }
}
