package com.shoplab.helpdesk.chat.support;

import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

/**
 * For the chat tests that predate the retrieval-augmented answers (LAB-12): the knowledge base knows nothing, so the
 * only Ollama endpoint they have to simulate is the chat one.
 */
@TestConfiguration
public class EmptyKnowledgeContextConfig {

    @Bean
    @Primary
    KnowledgeContextPort emptyKnowledgeContext() {
        return (question, limit) -> Mono.just(List.of());
    }
}
