package com.querysense.backend.exception;

/**
 * LlmUnavailableException
 *
 * WHY THIS EXISTS (it fixes a real bug, it isn't just plumbing):
 * The previous Gemini implementation returned a *string* when it gave up —
 * literally "-- Gemini unavailable, please try again shortly." — and that
 * string was then treated by QueryGenerationServiceImpl as if it were a
 * successfully generated query: labeled source=GEMINI with confidence 0.65,
 * returned to the caller with HTTP 200, and persisted to query_history.
 * Because the history table doubles as the Layer 2 cache, the next identical
 * request would then get a *cache hit* on that error text and return it with
 * confidence 1.0.
 *
 * That was survivable against a hosted API that is almost always up. It is
 * not survivable against a local Ollama runtime, where "the server isn't
 * running" or "the model isn't pulled" are ordinary, everyday states — the
 * history table would fill with comment strings within a single afternoon of
 * development.
 *
 * So an unreachable model is now an exception, not a return value. It never
 * reaches saveToHistory(), never enters the cache, and surfaces to the client
 * as a 503 with an actionable message (see GlobalExceptionHandler) rather
 * than a 200 containing a fake query.
 *
 * WHY UNCHECKED: it is not recoverable at the call site. There is no
 * secondary provider to fall back to by design (CLAUDE.md Section 2 — the
 * rule engine is the primary path and the LLM is already itself the
 * fallback), so the only correct handling is to report it upward.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(String message) {
        super(message);
    }

    public LlmUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
