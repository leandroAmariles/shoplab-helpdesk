package com.shoplab.helpdesk.chat.infrastructure.out.prompts;

import com.shoplab.helpdesk.chat.domain.model.enums.PromptName;
import com.shoplab.helpdesk.chat.infrastructure.out.prompts.CachedPromptCatalog.RefreshOutcome;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

/**
 * Keeps the prompts of the catalog up to date (LAB-85). At start-up it loads the four prompts in parallel and waits as
 * much as the request timeout: the application is not declared ready before, so with a healthy Langfuse the first turn
 * already uses the labelled version, and with Langfuse down the start costs one timeout at most and the chat begins
 * with the default texts. Then it asks again every interval, in the background: that interval is the longest a change
 * of label takes to reach the chat. The seeding of missing prompts runs after the load without holding the start.
 */
public final class PromptRefresher implements ApplicationRunner, DisposableBean {

    private static final Logger LOG = LoggerFactory.getLogger(PromptRefresher.class);
    private static final String THREAD_NAME = "prompt-refresher";

    private final CachedPromptCatalog catalog;
    private final PromptPublisher publisher;
    private final Duration refreshInterval;
    private final Duration startupWait;
    private final boolean publishMissing;
    private final ScheduledExecutorService scheduler;

    public PromptRefresher(CachedPromptCatalog catalog, PromptPublisher publisher, Duration refreshInterval,
            Duration requestTimeout, boolean publishMissing) {
        this(catalog, publisher, refreshInterval, requestTimeout, publishMissing, Executors
                .newSingleThreadScheduledExecutor(task -> {
                    Thread thread = new Thread(task, THREAD_NAME);
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    PromptRefresher(CachedPromptCatalog catalog, PromptPublisher publisher, Duration refreshInterval,
            Duration requestTimeout, boolean publishMissing, ScheduledExecutorService scheduler) {
        this.catalog = catalog;
        this.publisher = publisher;
        this.refreshInterval = refreshInterval;
        this.startupWait = requestTimeout.plusMillis(500);
        this.publishMissing = publishMissing;
        this.scheduler = scheduler;
    }

    @Override
    public void run(ApplicationArguments args) {
        Map<PromptName, RefreshOutcome> outcomes = loadAtStartup();
        if (publishMissing) {
            List<PromptName> missing = outcomes.entrySet().stream()
                    .filter(entry -> entry.getValue() == RefreshOutcome.MISSING)
                    .map(Map.Entry::getKey)
                    .toList();
            if (!missing.isEmpty()) {
                publisher.publishMissing(missing)
                        .flatMapIterable(created -> created)
                        .flatMap(catalog::refresh)
                        .subscribe(outcome -> { }, error -> LOG.warn("The seeding of the prompts failed: {}",
                                error.getClass().getSimpleName()));
            }
        }
        scheduler.scheduleWithFixedDelay(this::refreshInBackground, refreshInterval.toMillis(),
                refreshInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** One pass of the periodic refresh; a failure of it never stops the next one. */
    void refreshInBackground() {
        try {
            catalog.refreshAll().subscribe(outcomes -> { },
                    error -> LOG.warn("The refresh of the prompts failed: {}", error.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            LOG.warn("The refresh of the prompts failed: {}", e.getClass().getSimpleName());
        }
    }

    /**
     * Waits for the first load on the start-up thread, which is not part of any reactive chain, so the application is
     * not declared ready before it (or before one request timeout). A future is waited on, not a publisher: the wait
     * never happens on an event loop.
     */
    private Map<PromptName, RefreshOutcome> loadAtStartup() {
        CompletableFuture<Map<PromptName, RefreshOutcome>> load = catalog.refreshAll().toFuture();
        try {
            return load.get(startupWait.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            load.cancel(true);
        } catch (TimeoutException | ExecutionException | RuntimeException e) {
            load.cancel(true);
            LOG.warn("The prompts could not be loaded at start-up, the default texts are used: {}",
                    e.getClass().getSimpleName());
        }
        return Map.of();
    }

    @Override
    public void destroy() {
        scheduler.shutdownNow();
    }
}
