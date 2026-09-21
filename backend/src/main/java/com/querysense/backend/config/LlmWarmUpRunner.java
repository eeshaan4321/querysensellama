package com.querysense.backend.config;

import com.querysense.backend.service.LlmFallbackService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Loads the LLM model at startup so the first fallback request is fast. */
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
