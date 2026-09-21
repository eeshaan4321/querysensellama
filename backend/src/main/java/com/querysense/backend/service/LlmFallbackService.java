package com.querysense.backend.service;

/** LLM fallback for query generation. */
public interface LlmFallbackService {

    String generateQuery(String userInput, String databaseType);

    default void warmUp() {
    }
}
