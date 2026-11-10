package com.shoplab.helpdesk.retrieval.application.usecase;

import com.shoplab.helpdesk.retrieval.application.port.in.IngestDocumentServicePort;
import com.shoplab.helpdesk.retrieval.application.port.out.DocumentParserPort;
import com.shoplab.helpdesk.retrieval.application.port.out.IngestMetricsPort;
import com.shoplab.helpdesk.retrieval.application.port.out.KnowledgeBasePort;
import com.shoplab.helpdesk.retrieval.application.port.out.UploadMetricsPort;
import com.shoplab.helpdesk.retrieval.domain.model.DocumentIngestedEvent;
import com.shoplab.helpdesk.retrieval.domain.model.IngestDocumentCommand;
import com.shoplab.helpdesk.retrieval.domain.model.IngestedDocument;
import com.shoplab.helpdesk.retrieval.domain.model.enums.IngestOutcome;
import com.shoplab.helpdesk.shared.exception.BusinessException;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

@Service
public class IngestDocumentUseCase implements IngestDocumentServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(IngestDocumentUseCase.class);
    private static final int MAX_LOGGED_NAME = 200;

    private final DocumentParserPort parser;
    private final KnowledgeBasePort knowledgeBase;
    private final IngestMetricsPort metrics;
    private final UploadMetricsPort uploadMetrics;
    private final ApplicationEventPublisher events;

    public IngestDocumentUseCase(DocumentParserPort parser, KnowledgeBasePort knowledgeBase,
            IngestMetricsPort metrics, UploadMetricsPort uploadMetrics, ApplicationEventPublisher events) {
        this.parser = parser;
        this.knowledgeBase = knowledgeBase;
        this.metrics = metrics;
        this.uploadMetrics = uploadMetrics;
        this.events = events;
    }

    /**
     * The whole operation is measured and logged once with its outcome: name, result and chunks, never the text of
     * the document (Constitution VI).
     */
    @Override
    public Mono<IngestedDocument> ingest(IngestDocumentCommand command) {
        return Mono.defer(() -> {
            long startedAt = System.nanoTime();
            return store(command)
                    .doOnNext(stored -> {
                        uploadMetrics.recordUpload(IngestOutcome.OK, elapsedSince(startedAt));
                        LOG.info("Document upload finished: name={}, outcome={}, replaced={}, chunks={}, requestId={}",
                                loggable(command.name()), IngestOutcome.OK.tagValue(), stored.replaced(),
                                stored.chunkCount(), command.requestId());
                    })
                    .doOnError(error -> {
                        IngestOutcome outcome = outcomeOf(error);
                        uploadMetrics.recordUpload(outcome, elapsedSince(startedAt));
                        LOG.info("Document upload finished: name={}, outcome={}, requestId={}",
                                loggable(command.name()), outcome.tagValue(), command.requestId());
                    });
        });
    }

    private Mono<IngestedDocument> store(IngestDocumentCommand command) {
        return Mono.defer(() -> {
            validate(command);
            long startedAt = System.nanoTime();
            return parser.parse(command.name(), command.content())
                    .doOnSuccess(parsed -> metrics.recordParse(IngestOutcome.OK, elapsedSince(startedAt)))
                    .doOnError(error -> metrics.recordParse(outcomeOf(error), elapsedSince(startedAt)))
                    .flatMap(knowledgeBase::replaceDocument)
                    .doOnNext(stored -> {
                        LOG.info("Document ingested: source={}, documentId={}, chunks={}", stored.source(),
                                stored.documentId(), stored.chunkCount());
                        events.publishEvent(new DocumentIngestedEvent(stored.documentId(), stored.source()));
                    });
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

    /** The name comes from the client: control characters could forge log lines and its length is unbounded. */
    static String loggable(String name) {
        if (name == null) {
            return null;
        }
        String clean = name.replaceAll("\\p{Cntrl}", "_");
        return clean.length() > MAX_LOGGED_NAME ? clean.substring(0, MAX_LOGGED_NAME) + "..." : clean;
    }

    private static IngestOutcome outcomeOf(Throwable error) {
        return error instanceof BusinessException ? IngestOutcome.REJECTED : IngestOutcome.FAILED;
    }

    private static Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
