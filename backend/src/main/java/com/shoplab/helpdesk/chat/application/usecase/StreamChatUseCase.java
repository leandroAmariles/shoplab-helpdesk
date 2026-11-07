package com.shoplab.helpdesk.chat.application.usecase;

import com.shoplab.helpdesk.chat.application.port.in.StreamChatServicePort;
import com.shoplab.helpdesk.chat.application.port.out.ChatMetricsPort;
import com.shoplab.helpdesk.chat.application.port.out.ChatModelPort;
import com.shoplab.helpdesk.chat.application.port.out.KnowledgeContextPort;
import com.shoplab.helpdesk.chat.config.ChatProperties;
import com.shoplab.helpdesk.chat.config.RagProperties;
import com.shoplab.helpdesk.chat.domain.exception.ChatModelException;
import com.shoplab.helpdesk.chat.domain.model.AnswerSource;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.DoneEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.ErrorEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.SourcesEvent;
import com.shoplab.helpdesk.chat.domain.model.ChatEvent.TokenEvent;
import com.shoplab.helpdesk.chat.domain.model.ContextFragment;
import com.shoplab.helpdesk.chat.domain.model.ConversationId;
import com.shoplab.helpdesk.chat.domain.model.ConversationTurn;
import com.shoplab.helpdesk.chat.domain.model.HistorySelection;
import com.shoplab.helpdesk.chat.domain.model.StreamChatCommand;
import com.shoplab.helpdesk.chat.domain.model.enums.ChatOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.CitationOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.ContextOutcome;
import com.shoplab.helpdesk.chat.domain.model.enums.NoContextMode;
import com.shoplab.helpdesk.shared.exception.ExceptionUtil;
import com.shoplab.helpdesk.shared.exception.HelpdeskException;
import com.shoplab.helpdesk.shared.exception.ResponseCodeEnum;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
    private final ConversationHistory history;
    private final ChatMetricsPort metrics;
    private final ChatProperties properties;
    private final RagProperties ragProperties;

    public StreamChatUseCase(ChatModelPort chatModel, KnowledgeContextPort knowledgeContext,
            ContextSelector contextSelector, ConversationHistory history, ChatMetricsPort metrics,
            ChatProperties properties, RagProperties ragProperties) {
        this.chatModel = chatModel;
        this.knowledgeContext = knowledgeContext;
        this.contextSelector = contextSelector;
        this.history = history;
        this.metrics = metrics;
        this.properties = properties;
        this.ragProperties = ragProperties;
    }

    @Override
    public Flux<ChatEvent> stream(StreamChatCommand command) {
        return Flux.defer(() -> {
            validate(command);
            Optional<ConversationId> conversation = history.conversationOf(command);
            LOG.info("Chat request received: requestId={}, messageLength={}, conversation={}", command.requestId(),
                    command.message().length(), conversation.isPresent());
            long startedAt = System.nanoTime();
            AtomicInteger fragments = new AtomicInteger();
            AtomicBoolean firstTokenSeen = new AtomicBoolean();
            AtomicBoolean responseRecorded = new AtomicBoolean();
            AtomicBoolean fixedMessageAnswer = new AtomicBoolean();
            StringBuilder answerText = new StringBuilder();
            return conversation.map(id -> history.load(id, command.requestId()))
                    .orElseGet(() -> Mono.just(HistorySelection.empty()))
                    .flatMapMany(selection -> retrieveContext(command, selection)
                            .flatMapMany(context -> {
                                fixedMessageAnswer.set(answersWithFixedMessage(context));
                                return events(command, selection.turns(), context, startedAt, fragments,
                                        firstTokenSeen, fixedMessageAnswer, answerText);
                            }))
                    .concatWith(Mono.defer(() -> finish(command, conversation.orElse(null), answerText,
                            fragments)))
                    .doOnComplete(() -> record(responseRecorded, ChatOutcome.COMPLETED, startedAt))
                    .doOnCancel(() -> record(responseRecorded, ChatOutcome.CANCELLED, startedAt))
                    .onErrorResume(error -> {
                        record(responseRecorded, ChatOutcome.ERROR, startedAt);
                        return failed(command, error, firstTokenSeen.get());
                    });
        });
    }

    /**
     * The tokens of the answer and, just before the first one, the sources it is generated from. Sending them with the
     * first token and not earlier keeps a model that fails before answering a plain error response, as in LAB-12.
     * The text of the answer is gathered so that, once complete, it can be stored as part of the conversation.
     */
    private Flux<ChatEvent> events(StreamChatCommand command, List<ConversationTurn> turns,
            List<ContextFragment> context, long startedAt, AtomicInteger fragments, AtomicBoolean firstTokenSeen,
            AtomicBoolean fixedMessageAnswer, StringBuilder answerText) {
        Optional<SourcesEvent> sources = sourcesOf(context);
        AtomicBoolean sourcesPending = new AtomicBoolean(sources.isPresent());
        CitationTracker citations = new CitationTracker();
        return answer(command, turns, context)
                .doOnNext(citations::append)
                .doOnNext(answerText::append)
                .doOnNext(content -> {
                    // The configured message is not a model token: timing it would mix ~0 ms replies into the
                    // model's first-token latency. It is still counted by the context and response metrics.
                    if (firstTokenSeen.compareAndSet(false, true) && !fixedMessageAnswer.get()) {
                        metrics.recordFirstToken(elapsedSince(startedAt));
                    }
                })
                .concatMapIterable(content -> {
                    fragments.incrementAndGet();
                    ChatEvent token = new TokenEvent(content);
                    if (sourcesPending.compareAndSet(true, false) && sources.isPresent()) {
                        return List.of(sources.get(), token);
                    }
                    return List.of(token);
                })
                .doOnComplete(() -> sources.ifPresent(event -> recordCitations(command, event, citations)));
    }

    /**
     * Whether the answer cites the sources it was given. Only a completed answer with context and some text is
     * judged. The log carries counts, never the question, the answer or the fragments.
     */
    private void recordCitations(StreamChatCommand command, SourcesEvent sources, CitationTracker citations) {
        if (citations.isEmpty()) {
            return;
        }
        CitationTracker.Summary summary = citations.summarize(sources.sources().size());
        metrics.recordCitations(summary.outcome());
        if (summary.outcome() == CitationOutcome.INVALID) {
            LOG.warn("The answer cites sources it was not given: requestId={}, valid={}, invalid={}, sources={}",
                    command.requestId(), summary.valid(), summary.invalid(), sources.sources().size());
        }
    }

    /** What a citation {@code [n]} points at: the fragments handed to the model, numbered in that order. */
    private static Optional<SourcesEvent> sourcesOf(List<ContextFragment> context) {
        if (context.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new SourcesEvent(IntStream.range(0, context.size())
                .mapToObj(index -> AnswerSource.of(index, context.get(index)))
                .toList()));
    }

    /** Without useful context the answer may be the configured message, and then the model is not called at all. */
    private Flux<String> answer(StreamChatCommand command, List<ConversationTurn> turns,
            List<ContextFragment> context) {
        if (answersWithFixedMessage(context)) {
            return Flux.just(ragProperties.noContextMessage());
        }
        return chatModel.streamAnswer(command.message(), turns, context);
    }

    /**
     * The last event. A finished answer is stored before {@code done} is sent, so the follow-up the customer writes
     * after seeing {@code done} always finds this turn. A failed or cancelled answer never gets here, and an answer
     * with no text is not a turn.
     *
     * @param conversation the conversation of the request, or {@code null} when it has none (no memory)
     */
    private Mono<ChatEvent> finish(StreamChatCommand command, ConversationId conversation, StringBuilder answerText,
            AtomicInteger fragments) {
        Mono<ChatEvent> done = Mono.fromSupplier(() -> new DoneEvent(fragments.get()));
        if (conversation == null || answerText.toString().isBlank()) {
            return done;
        }
        return history.save(conversation, command.requestId(), command.message(), answerText.toString()).then(done);
    }

    private boolean answersWithFixedMessage(List<ContextFragment> context) {
        return context.isEmpty() && ragProperties.noContextMode() == NoContextMode.FIXED_MESSAGE;
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
    private Mono<List<ContextFragment>> retrieveContext(StreamChatCommand command, HistorySelection selection) {
        long startedAt = System.nanoTime();
        AtomicInteger candidatesFound = new AtomicInteger();
        return knowledgeContext.retrieve(history.searchText(command.message(), selection), ragProperties.topK())
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
