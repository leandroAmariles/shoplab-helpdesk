package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.application.port.out.DocumentParserPort;
import com.shoplab.helpdesk.retrieval.application.port.out.IngestMetricsPort;
import com.shoplab.helpdesk.retrieval.application.port.out.KnowledgeBasePort;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class IngestDocumentUseCase implements IngestDocumentServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(IngestDocumentUseCase.class);

    private final DocumentParserPort parser;
    private final KnowledgeBasePort knowledgeBase;
    private final IngestMetricsPort metrics;

    public IngestDocumentUseCase(DocumentParserPort parser, KnowledgeBasePort knowledgeBase,
            IngestMetricsPort metrics) {
        this.parser = parser;
        this.knowledgeBase = knowledgeBase;
        this.metrics = metrics;
    }

    @Override
    public Mono<IngestedDocument> ingest(IngestDocumentCommand command) {
        return Mono.defer(() -> {
            validate(command);
            long startedAt = System.nanoTime();
            return parser.parse(command.name(), command.content())
                    .doOnSuccess(parsed -> metrics.recordParse(IngestOutcome.OK, elapsedSince(startedAt)))
                    .doOnError(error -> metrics.recordParse(outcomeOf(error), elapsedSince(startedAt)))
                    .flatMap(knowledgeBase::replaceDocument)
                    .doOnNext(stored -> LOG.info("Document ingested: source={}, documentId={}, chunks={}",
                            stored.source(), stored.documentId(), stored.chunkCount()));
        });
    }

    private static void validate(IngestDocumentCommand command) {
        boolean validName = command.name() != null && !command.name().isBlank()
                && command.name().indexOf('/') < 0 && command.name().indexOf('\\') < 0;
        boolean hasContent = command.content() != null && command.content().length > 0;
        if (!validName || !hasContent) {
            throw ExceptionUtil.business(ResponseCodeEnum.DOCUMENT_INVALID);
        }
    }

    private static IngestOutcome outcomeOf(Throwable error) {
        return error instanceof BusinessException ? IngestOutcome.REJECTED : IngestOutcome.FAILED;
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
