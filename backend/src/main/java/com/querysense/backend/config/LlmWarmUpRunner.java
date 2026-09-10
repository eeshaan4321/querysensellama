package com.querysense.backend.config;

import com.querysense.backend.service.LlmFallbackService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * LlmWarmUpRunner
 *
 * WHY THIS EXISTS:
 * Ollama loads a model into VRAM lazily, on first use. For a ~4.7GB 7B model
 * that costs roughly 2-4 seconds of disk-to-VRAM transfer, and it is paid by
 * whichever request happens to arrive first. Without this, that request is
 * the first query someone types during a demo — the one moment where a
 * four-second pause is most expensive and least explicable. Firing a
 * throwaway one-token request at startup moves that cost to a point where
 * nobody is watching.
 *
 * WHY IT RUNS ON A SEPARATE THREAD:
 * ApplicationReadyEvent handlers run synchronously on the main thread, so
 * warming up inline would add those seconds to every `spring-boot:run`,
 * including the many restarts during development that never touch the LLM at
 * all. The thread is a daemon so it can never hold up JVM shutdown.
 *
 * WHY IT IS ALLOWED TO FAIL SILENTLY:
 * QuerySense is rule-based-first by design (CLAUDE.md Section 2). The rule
 * engine, cache, query analysis, domain/resource optimization, schema advisor
 * and history modules are all fully functional with no LLM present. A backend
 * that refused to start — or logged alarming errors — because an optional
 * fallback wasn't warmed would invert that relationship. If Ollama is down,
 * the first actual fallback request reports it properly via
 * LlmUnavailableException; that is the right place for the complaint.
 *
 * Disable with `ollama.warmup.enabled=false` (e.g. on a machine where holding
 * ~5GB of VRAM for the keep_alive window is unwelcome).
 */
@Component
public class LlmWarmUpRunner {

    private final LlmFallbackService llmFallbackService;

    @Value("${ollama.warmup.enabled:true}")
    private boolean warmUpEnabled;

    public LlmWarmUpRunner(LlmFallbackService llmFallbackService) {
        this.llmFallbackService = llmFallbackService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUpOnStartup() {
        if (!warmUpEnabled) {
            return;
        }
        Thread warmUp = new Thread(llmFallbackService::warmUp, "llm-warmup");
        warmUp.setDaemon(true);
        warmUp.start();
    }
}
