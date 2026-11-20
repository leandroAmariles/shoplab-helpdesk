package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.shoplab.helpdesk.chat.application.port.out.PromptCatalogPort;
import com.shoplab.helpdesk.chat.application.usecase.PromptValidator;
import com.shoplab.helpdesk.chat.domain.exception.PromptOverrideException;
import com.shoplab.helpdesk.chat.domain.model.PromptReference;
import com.shoplab.helpdesk.chat.domain.model.ResolvedPrompt;
import com.shoplab.helpdesk.chat.domain.model.constants.PromptConstants;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.domain.model.enums.PromptRejection;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.LangfusePromptClient.FetchResult;
import com.shoplab.helpdesk.chat.infrastructure.support.KeyedWarnThrottle;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The prompts of the chat, kept in memory (LAB-85). A turn only reads the last valid version of each prompt, so the
 * state of Langfuse can never delay or break it. The versions are refreshed in the background ({@link #refreshAll()}):
 * a version replaces the loaded one only when it is valid and different, and what is loaded never expires, so a
 * Langfuse that stops answering, a label that is removed or a bad edit keeps the chat on the last valid text. While
 * nothing valid has been loaded the default text is served.
 *
 * <p>A version fixed by an evaluation ({@link #pinned}) does not go through the refresh: a version of Langfuse never
 * changes, so each one is read once and kept in a small cache.
 */
public final class CachedPromptCatalog implements PromptCatalogPort {

    private static final Logger LOG = LoggerFactory.getLogger(CachedPromptCatalog.class);
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(5);

    /** What a refresh of one prompt ended in; it is also the value of the {@code result} tag of the metric. */
    public enum RefreshOutcome {
        UPDATED(PromptConstants.RESULT_UPDATED),
        UNCHANGED(PromptConstants.RESULT_UNCHANGED),
        INVALID(PromptConstants.RESULT_INVALID),
        MISSING(PromptConstants.RESULT_MISSING),
        FAILED(PromptConstants.RESULT_FAILED);

        private final String tag;

        RefreshOutcome(String tag) {
            this.tag = tag;
        }

        public String tag() {
            return tag;
        }
    }

    private record PinnedKey(PromptName name, int version) {
    }

    private final LangfusePromptClient client;
    private final PromptValidator validator;
    private final PromptDefaults defaults;
    private final String label;
    private final MeterRegistry meters;
    private final KeyedWarnThrottle warnings;
    private final Map<PromptName, AtomicReference<ResolvedPrompt>> loaded = new EnumMap<>(PromptName.class);
    private final Map<PinnedKey, ResolvedPrompt> pinnedVersions = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<PinnedKey, ResolvedPrompt> eldest) {
            return size() > PromptConstants.PINNED_CACHE_SIZE;
        }
    };

    public CachedPromptCatalog(LangfusePromptClient client, PromptValidator validator, PromptDefaults defaults,
            String label, MeterRegistry meters) {
        this(client, validator, defaults, label, meters, new KeyedWarnThrottle(WARNING_INTERVAL));
    }

    CachedPromptCatalog(LangfusePromptClient client, PromptValidator validator, PromptDefaults defaults, String label,
            MeterRegistry meters, KeyedWarnThrottle warnings) {
        this.client = client;
        this.validator = validator;
        this.defaults = defaults;
        this.label = label;
        this.meters = meters;
        this.warnings = warnings;
        for (PromptName name : PromptName.values()) {
            AtomicReference<ResolvedPrompt> reference = new AtomicReference<>();
            loaded.put(name, reference);
            Gauge.builder(PromptConstants.LOADED_VERSION_METRIC, reference, CachedPromptCatalog::versionOf)
                    .tag(PromptConstants.PROMPT_TAG, name.langfuseName())
                    .register(meters);
        }
    }

    /** Memory only: the last valid version loaded, or the default text while there is none. */
    @Override
    public ResolvedPrompt current(PromptName name) {
        ResolvedPrompt prompt = loaded.get(name).get();
        return prompt != null ? prompt : defaults.get(name);
    }

    @Override
    public Mono<ResolvedPrompt> pinned(PromptName name, int version) {
        PinnedKey key = new PinnedKey(name, version);
        ResolvedPrompt cached;
        synchronized (pinnedVersions) {
            cached = pinnedVersions.get(key);
        }
        if (cached != null) {
            return Mono.just(cached);
        }
        return client.fetchByVersion(name.langfuseName(), version)
                .flatMap(result -> {
                    Optional<ResolvedPrompt> prompt = usable(name, result, version);
                    if (prompt.isEmpty()) {
                        return Mono.<ResolvedPrompt>error(PromptOverrideException.unavailable());
                    }
                    synchronized (pinnedVersions) {
                        pinnedVersions.put(key, prompt.get());
                    }
                    return Mono.just(prompt.get());
                });
    }

    /** Asks Langfuse for the four prompts in parallel; it never fails. */
    public Mono<Map<PromptName, RefreshOutcome>> refreshAll() {
        return Flux.fromArray(PromptName.values())
                .flatMap(name -> refresh(name).map(outcome -> Map.entry(name, outcome)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, () -> new EnumMap<>(PromptName.class));
    }

    /** Asks Langfuse for the version of one prompt that carries the label and applies it if it is valid and new. */
    public Mono<RefreshOutcome> refresh(PromptName name) {
        return client.fetchByLabel(name.langfuseName(), label)
                .map(result -> apply(name, result))
                .doOnNext(outcome -> count(name, outcome));
    }

    private RefreshOutcome apply(PromptName name, FetchResult result) {
        if (result.outcome() == LangfusePromptClient.FetchOutcome.MISSING) {
            warn(name, PromptConstants.REASON_MISSING, null);
            return RefreshOutcome.MISSING;
        }
        if (result.outcome() == LangfusePromptClient.FetchOutcome.FAILED) {
            warn(name, PromptConstants.REASON_FAILED, result.detail());
            return RefreshOutcome.FAILED;
        }
        Optional<PromptRejection> rejection = validator.validate(name, result.type(), result.text());
        if (rejection.isPresent()) {
            warn(name, rejection.get().reason(), "version=" + result.version());
            return RefreshOutcome.INVALID;
        }
        AtomicReference<ResolvedPrompt> slot = loaded.get(name);
        ResolvedPrompt current = slot.get();
        boolean same = current != null && current.reference().version().equals(result.version())
                && current.text().equals(result.text());
        if (same) {
            return RefreshOutcome.UNCHANGED;
        }
        slot.set(new ResolvedPrompt(result.text(), PromptReference.langfuse(name, result.version())));
        LOG.info("Prompt loaded: prompt={}, version={}", name.langfuseName(), result.version());
        return RefreshOutcome.UPDATED;
    }

    private Optional<ResolvedPrompt> usable(PromptName name, FetchResult result, int version) {
        if (result.outcome() != LangfusePromptClient.FetchOutcome.FOUND) {
            warn(name, result.outcome() == LangfusePromptClient.FetchOutcome.MISSING
                    ? PromptConstants.REASON_MISSING : PromptConstants.REASON_FAILED,
                    "version=" + version + (result.detail() == null ? "" : ", " + result.detail()));
            return Optional.empty();
        }
        Optional<PromptRejection> rejection = validator.validate(name, result.type(), result.text());
        if (rejection.isPresent()) {
            warn(name, rejection.get().reason(), "version=" + version);
            return Optional.empty();
        }
        return Optional.of(new ResolvedPrompt(result.text(), PromptReference.langfuse(name, result.version())));
    }

    /** A warning with the prompt, the reason and, when there is one, a short detail: never the text of the prompt. */
    private void warn(PromptName name, String reason, String detail) {
        if (warnings.allow("prompt:" + name.langfuseName() + ":" + reason)) {
            LOG.warn("Prompt not usable, the last valid version stays in use: prompt={}, reason={}{}",
                    name.langfuseName(), reason, detail == null ? "" : ", " + detail);
        }
    }

    private void count(PromptName name, RefreshOutcome outcome) {
        meters.counter(PromptConstants.REFRESH_METRIC, PromptConstants.PROMPT_TAG, name.langfuseName(),
                PromptConstants.RESULT_TAG, outcome.tag()).increment();
    }

    private static double versionOf(AtomicReference<ResolvedPrompt> reference) {
        ResolvedPrompt prompt = reference.get();
        return prompt == null || prompt.reference().version() == null ? 0 : prompt.reference().version();
    }
}
