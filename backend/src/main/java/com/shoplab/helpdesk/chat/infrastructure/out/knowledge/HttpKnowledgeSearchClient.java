package com.shoplab.helpdesk.chat.infrastructure.out.knowledge;

import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.domain.exception.KnowledgeRetrievalException;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Reactive HTTP client for {@code GET /api/knowledge/search} on {@code retrieval}.
 * Enabled via {@code shoplab.retrieval.client.mode=http} for service extraction (LAB-106 / LAB-129).
 */
@Component
@ConditionalOnProperty(name = "shoplab.retrieval.client.mode", havingValue = "http")
public class HttpKnowledgeSearchClient implements KnowledgeContextPort {

    private static final Logger LOG = LoggerFactory.getLogger(HttpKnowledgeSearchClient.class);

    private final WebClient webClient;

    @Autowired
    public HttpKnowledgeSearchClient(
            WebClient.Builder webClientBuilder,
            @Value("${shoplab.retrieval.client.base-url:http://localhost:8080}") String baseUrl) {
        this(webClientBuilder.baseUrl(baseUrl).build());
    }

    public HttpKnowledgeSearchClient(WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public Mono<List<ContextFragment>> retrieve(String question, int limit) {
        return webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/api/knowledge/search")
                        .queryParam("query", question)
                        .queryParam("limit", limit)
                        .build())
                .retrieve()
                .bodyToMono(SearchApiResponse.class)
                .map(response -> toFragments(response.results()))
                .onErrorMap(KnowledgeRetrievalException::new);
    }

    private List<ContextFragment> toFragments(List<SearchHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        return hits.stream()
                .filter(hit -> {
                    boolean usable = hit.text() != null && !hit.text().isBlank();
                    if (!usable) {
                        LOG.warn("Dropped blank chunk from HTTP retrieval: source={}, position={}",
                                hit.source(), hit.position());
                    }
                    return usable;
                })
                .map(HttpKnowledgeSearchClient::toFragment)
                .toList();
    }

    private static ContextFragment toFragment(SearchHit hit) {
        return new ContextFragment(
                hit.source(),
                titleOf(hit),
                hit.section() == null ? "" : hit.section(),
                hit.position(),
                hit.text(),
                hit.score());
    }

    private static String titleOf(SearchHit hit) {
        if (hit.title() != null && !hit.title().isBlank()) {
            return hit.title().strip();
        }
        String name = hit.source();
        int extension = name.lastIndexOf('.');
        String words = (extension > 0 ? name.substring(0, extension) : name).replaceAll("[-_]+", " ").strip();
        return words.isEmpty() ? name : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    }

    private record SearchApiResponse(String model, List<SearchHit> results) {
    }

    private record SearchHit(String source, String title, String section, int position, double score, String text) {
    }
}
