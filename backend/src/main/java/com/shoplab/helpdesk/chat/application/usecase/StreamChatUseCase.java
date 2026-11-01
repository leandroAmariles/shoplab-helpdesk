package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.application.port.in.StreamChatServicePort;
import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class StreamChatUseCase implements StreamChatServicePort {

    private static final Logger LOG = LoggerFactory.getLogger(StreamChatUseCase.class);

    private final ChatModelPort chatModel;
    private final KnowledgeContextPort knowledgeContext;
    private final ContextSelector contextSelector;
    private final ChatMetricsPort metrics;
    private final ChatProperties properties;
    private final RagProperties ragProperties;

    public StreamChatUseCase(ChatModelPort chatModel, KnowledgeContextPort knowledgeContext,
            ContextSelector contextSelector, ChatMetricsPort metrics, ChatProperties properties,
            RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.knowledgeContext = knowledgeContext;
        this.contextSelector = contextSelector;
        this.metrics = metrics;
        this.properties = properties;
        this.ragProperties = ragProperties;
    }

    @Override
    public Flux<ChatEvent> stream(StreamChatCommand command) {
        return Flux.defer(() -> {
            validate(command);
            LOG.info("Chat request received: requestId={}, messageLength={}", command.requestId(),
                    command.message().length());
            long startedAt = System.nanoTime();
            AtomicInteger fragments = new AtomicInteger();
            AtomicBoolean firstTokenSeen = new AtomicBoolean();
            AtomicBoolean responseRecorded = new AtomicBoolean();
            return retrieveContext(command)
                    .flatMapMany(context -> answer(command, context))
                    .doOnNext(content -> {
                        if (firstTokenSeen.compareAndSet(false, true)) {
                            metrics.recordFirstToken(elapsedSince(startedAt));
                        }
                    })
                    .map(content -> {
                        fragments.incrementAndGet();
                        return (ChatEvent) new TokenEvent(content);
                    })
                    .concatWith(Mono.fromSupplier(() -> new DoneEvent(fragments.get())))
                    .doOnComplete(() -> record(responseRecorded, ChatOutcome.COMPLETED, startedAt))
                    .doOnCancel(() -> record(responseRecorded, ChatOutcome.CANCELLED, startedAt))
                    .onErrorResume(error -> {
                        record(responseRecorded, ChatOutcome.ERROR, startedAt);
                        return failed(command, error, firstTokenSeen.get());
                    });
        });
    }

    /** Without useful context the answer may be the configured message, and then the model is not called at all. */
    private Flux<String> answer(StreamChatCommand command, List<ContextFragment> context) {
        if (context.isEmpty() && ragProperties.noContextMode() == NoContextMode.FIXED_MESSAGE) {
            return Flux.just(ragProperties.noContextMessage());
        }
        return chatModel.streamAnswer(command.message(), context);
    }

    private void validate(StreamChatCommand command) {
        String message = command.message();
        if (message == null || message.isBlank() || message.length() > properties.maxMessageLength()) {
            LOG.warn("Chat request rejected: requestId={}, code={}", command.requestId(),
                    ResponseCodeEnum.INVALID_CHAT_MESSAGE.getCode());
            throw ExceptionUtil.business(ResponseCodeEnum.INVALID_CHAT_MESSAGE);
        }
    }

    /**
     * The fragments the answer may rely on. A failure of the search is counted here and travels on as the flux's
     * error, which {@code failed} logs once: the answer is never generated as if the context had been there.
     */
    private Mono<List<ContextFragment>> retrieveContext(StreamChatCommand command) {
        long startedAt = System.nanoTime();
        AtomicInteger candidatesFound = new AtomicInteger();
        return knowledgeContext.retrieve(command.message(), ragProperties.topK())
                .defaultIfEmpty(List.of())
                .doOnError(error -> metrics.recordContext(ContextOutcome.FAILED, 0, elapsedSince(startedAt)))
                .doOnNext(candidates -> candidatesFound.set(candidates.size()))
                .map(candidates -> contextSelector.select(candidates, ragProperties.similarityThreshold(),
                        ragProperties.maxContextCharacters()))
                .doOnNext(selected -> recordContext(command, selected, candidatesFound.get(), startedAt));
    }

    private void recordContext(StreamChatCommand command, List<ContextFragment> selected, int candidates,
            long startedAt) {
        ContextOutcome outcome = selected.isEmpty() ? ContextOutcome.NO_CONTEXT : ContextOutcome.WITH_CONTEXT;
        metrics.recordContext(outcome, selected.size(), elapsedSince(startedAt));
        double bestScore = selected.stream().mapToDouble(ContextFragment::score).max().orElse(0.0);
        if (selected.isEmpty()) {
            LOG.warn("No context for the answer: requestId={}, candidates={}, outcome={}", command.requestId(),
                    candidates, outcome.tagValue());
        } else {
            LOG.info("Context retrieved: requestId={}, candidates={}, delivered={}, bestScore={}, outcome={}",
                    command.requestId(), candidates, selected.size(), bestScore, outcome.tagValue());
        }
    }

    /** Before the first fragment the failure is the flux's error (an HTTP error); afterwards it is a last event. */
    private Flux<ChatEvent> failed(StreamChatCommand command, Throwable error, boolean streamStarted) {
        ResponseCodeEnum code = codeOf(error, streamStarted);
        LOG.warn("Chat answer failed: requestId={}, code={}, streamStarted={}", command.requestId(), code.getCode(),
                streamStarted, error);
        if (!streamStarted) {
            return Flux.error(error);
        }
        return Flux.just(new ErrorEvent(code.getCode(), code.getDefaultMessage()));
    }

    /** A model failure means "unavailable" before the first fragment and "broke half-way" after it. */
    private ResponseCodeEnum codeOf(Throwable error, boolean streamStarted) {
        if (error instanceof ChatModelException) {
            return streamStarted ? ResponseCodeEnum.CHAT_STREAM_FAILED : ResponseCodeEnum.CHAT_MODEL_UNAVAILABLE;
        }
        return error instanceof HelpdeskException typed ? typed.getResponseCode() : ResponseCodeEnum.CHAT_STREAM_FAILED;
    }

    /** A response is recorded once, whichever of completion, failure or cancellation happens first. */
    private void record(AtomicBoolean responseRecorded, ChatOutcome outcome, long startedAt) {
        if (responseRecorded.compareAndSet(false, true)) {
            metrics.recordResponse(outcome, elapsedSince(startedAt));
        }
    }

    private Duration elapsedSince(long startedAt) {
        return Duration.ofNanos(System.nanoTime() - startedAt);
    }
}
