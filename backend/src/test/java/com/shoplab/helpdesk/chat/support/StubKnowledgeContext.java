package com.shoplab.helpdesk.chat.support;

import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import reactor.core.publisher.Mono;

/**
 * Stands for retrieval-service in the tests of the whole chat (LAB-129): each test says what the search answers. The
 * search itself is tested in shoplab-retrieval-service, and the client that calls it in HttpKnowledgeSearchClientTests.
 */
public class StubKnowledgeContext implements KnowledgeContextPort {

    private final List<String> questions = new CopyOnWriteArrayList<>();
    private volatile Function<String, List<ContextFragment>> search = question -> List.of();
    private volatile boolean failing;

    @Override
    public Mono<List<ContextFragment>> retrieve(String question, int limit) {
        return Mono.defer(() -> {
            questions.add(question);
            if (failing) {
                return Mono.error(new KnowledgeRetrievalException(new IllegalStateException("simulated")));
            }
            return Mono.just(search.apply(question).stream().limit(limit).toList());
        });
    }

    public void answers(ContextFragment... fragments) {
        List<ContextFragment> all = List.of(fragments);
        this.search = question -> all;
    }

    public void answersWhen(Function<String, List<ContextFragment>> behaviour) {
        this.search = behaviour;
    }

    public void fails() {
        this.failing = true;
    }

    public void reset() {
        this.search = question -> List.of();
        this.failing = false;
        questions.clear();
    }

    public List<String> questions() {
        return List.copyOf(questions);
    }
}
