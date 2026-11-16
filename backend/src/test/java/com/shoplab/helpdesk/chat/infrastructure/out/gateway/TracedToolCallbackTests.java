package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.shoplab.helpdesk.chat.application.port.out.TurnTrace;
import com.shoplab.helpdesk.chat.domain.model.ToolCallTrace;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

class TracedToolCallbackTests {

    private final ToolCallback delegate = mock(ToolCallback.class);
    private final TurnTrace trace = mock(TurnTrace.class);
    private final TracedToolCallback traced = new TracedToolCallback(delegate, trace);

    {
        ToolDefinition definition = mock(ToolDefinition.class);
        when(definition.name()).thenReturn("lookupOrder");
        when(delegate.getToolDefinition()).thenReturn(definition);
    }

    @Test
    void theCallGoesThroughAndTheTraceGetsTheNameTheArgumentsTheResultAndTheTime() {
        when(delegate.call("{\"id\":7}")).thenReturn("shipped");

        String result = traced.call("{\"id\":7}");

        assertThat(result).isEqualTo("shipped");
        ArgumentCaptor<ToolCallTrace> call = ArgumentCaptor.forClass(ToolCallTrace.class);
        ArgumentCaptor<Duration> elapsed = ArgumentCaptor.forClass(Duration.class);
        verify(trace).toolCall(call.capture(), elapsed.capture());
        assertThat(call.getValue()).isEqualTo(new ToolCallTrace("lookupOrder", "{\"id\":7}", "shipped", false, null));
        assertThat(elapsed.getValue().isNegative()).isFalse();
    }

    @Test
    void theVariantWithContextIsTracedToo() {
        ToolContext toolContext = new ToolContext(java.util.Map.of());
        when(delegate.call("{}", toolContext)).thenReturn("ok");

        assertThat(traced.call("{}", toolContext)).isEqualTo("ok");

        verify(trace).toolCall(any(ToolCallTrace.class), any(Duration.class));
    }

    @Test
    void aFailureIsRethrownAsItCameAndTheTraceOnlyKeepsItsKind() {
        IllegalStateException failure = new IllegalStateException("secret detail of the customer");
        when(delegate.call("{}")).thenThrow(failure);

        assertThatThrownBy(() -> traced.call("{}")).isSameAs(failure);

        ArgumentCaptor<ToolCallTrace> call = ArgumentCaptor.forClass(ToolCallTrace.class);
        verify(trace).toolCall(call.capture(), any(Duration.class));
        assertThat(call.getValue().failed()).isTrue();
        assertThat(call.getValue().result()).isNull();
        assertThat(call.getValue().errorMessage()).isEqualTo("IllegalStateException");
    }

    @Test
    void aTraceThatFailsNeverChangesWhatTheModelGets() {
        when(delegate.call("{}")).thenReturn("ok");
        doThrow(new IllegalStateException("trace down")).when(trace).toolCall(any(), any());

        assertThat(traced.call("{}")).isEqualTo("ok");
    }

    @Test
    void theDefinitionAndTheMetadataAreTheOnesOfTheWrappedTool() {
        assertThat(traced.getToolDefinition().name()).isEqualTo("lookupOrder");
        assertThat(traced.getToolMetadata()).isEqualTo(delegate.getToolMetadata());
    }
}
