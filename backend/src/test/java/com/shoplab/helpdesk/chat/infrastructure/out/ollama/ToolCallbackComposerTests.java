package com.shoplab.helpdesk.chat.infrastructure.out.ollama;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.RootBeanDefinition;

/** {@code chat} only composes what other modules publish; it never knows who they are (specs/LAB-105, US1). */
class ToolCallbackComposerTests {

    private final ToolCallback first = newCallback();
    private final ToolCallback second = newCallback();

    @Test
    void joinsTheCallbacksOfEveryProviderInOrder() {
        ToolCallbackComposer composer = composerOf(provider(first), provider(second));

        assertThat(composer.compose()).containsExactly(first, second);
    }

    @Test
    void isEmptyWithoutProviders() {
        assertThat(composerOf().compose()).isEmpty();
    }

    @Test
    void aProviderWithoutCallbacksAddsNothing() {
        ToolCallbackComposer composer = composerOf(provider(), provider(second));

        assertThat(composer.compose()).containsExactly(second);
    }

    private static ToolCallback newCallback() {
        return mock(ToolCallback.class);
    }

    private static ToolCallbackProvider provider(ToolCallback... callbacks) {
        ToolCallbackProvider provider = mock(ToolCallbackProvider.class);
        when(provider.getToolCallbacks()).thenReturn(callbacks);
        return provider;
    }

    private static ToolCallbackComposer composerOf(ToolCallbackProvider... providers) {
        DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
        for (int index = 0; index < providers.length; index++) {
            ToolCallbackProvider provider = providers[index];
            RootBeanDefinition definition = new RootBeanDefinition(ToolCallbackProvider.class, () -> provider);
            definition.setScope(BeanDefinition.SCOPE_SINGLETON);
            beans.registerBeanDefinition("provider" + index, definition);
        }
        return new ToolCallbackComposer(beans.getBeanProvider(ToolCallbackProvider.class));
    }
}
