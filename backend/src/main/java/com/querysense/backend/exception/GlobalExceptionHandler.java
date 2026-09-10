package com.querysense.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * GlobalExceptionHandler
 *
 * WHY THIS CLASS EXISTS:
 * Without this, any unhandled exception in your app returns a generic
 * Spring "Whitelabel Error Page" or a raw 500 JSON with a huge stack trace.
 * That's terrible for API consumers — they don't know what went wrong.
 *
 * @RestControllerAdvice tells Spring:
 * "This class intercepts exceptions thrown anywhere in any @RestController
 * and lets you return a clean, structured response instead of crashing."
 *
 * Think of it as a safety net around your entire API layer.
 *
 * HOW IT WORKS:
 * Each method annotated with @ExceptionHandler handles one type of exception.
 * When that exception is thrown anywhere in the app, Spring routes it here
 * instead of propagating it to the client as a 500 error.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * Builds a consistent error response body.
     * WHY a helper method?
     * Every error response should look the same — same fields, same structure.
     * This prevents different handlers returning differently shaped JSON,
     * which would confuse frontend developers consuming the API.
     *
     * Response shape:
     * {
     *   "timestamp": "2026-06-12T10:30:00",
     *   "status": 400,
     *   "error": "Bad Request",
     *   "message": "Query input must not be blank"
     * }
     */
    private Map<String, Object> buildError(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return body;
    }

    /**
     * Handles @Valid / @NotBlank validation failures.
     *
     * WHY: When a request body fails validation (e.g. empty "query" field),
     * Spring throws MethodArgumentNotValidException. Without this handler,
     * the client gets a confusing 400 with Spring's internal error format.
     * Here we extract the actual field error message and return it cleanly.
     *
     * Triggered by: @Valid on @RequestBody in the controller
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationError(
            MethodArgumentNotValidException ex) {

        // Extract the first field error message
        // e.g. "query: must not be blank"
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, message));
    }

    /**
     * Handles an unreachable or uninstalled local model.
     *
     * WHY THIS IS THE MOST IMPORTANT HANDLER IN THE CLASS NOW:
     * Moving from a hosted API to a local runtime changes which failures are
     * *ordinary*. "Google is down" was rare; "Ollama isn't running yet" and
     * "nobody pulled the model on this machine" are everyday states on a
     * teammate's laptop. So this failure needs to produce something a
     * developer can act on immediately rather than a generic 500 — the
     * messages carried by LlmUnavailableException already name the exact
     * command to run, and are passed through verbatim here.
     *
     * 503 rather than 500: nothing is broken in this application. An optional
     * dependency isn't available, and the request may well succeed once it is.
     * Everything else in QuerySense — the rule engine, analysis, domain and
     * resource optimization, schema advice, history — keeps working meanwhile.
     *
     * Triggered by: OllamaService, when the runtime can't be reached or the
     * configured model isn't installed.
     */
    @ExceptionHandler(LlmUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleLlmUnavailable(
            LlmUnavailableException ex) {

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage()));
    }

    /**
     * Handles 4xx responses from the Ollama HTTP API.
     *
     * The previous version of this handler decoded Gemini's 401 (bad API key)
     * and 429 (rate limit) — neither of which can occur any more, because
     * there is no API key and no rate limit on a local runtime. 404 (model not
     * installed) is caught in OllamaService itself and rethrown as
     * LlmUnavailableException with the exact `ollama pull` command, so it
     * doesn't reach here either. What's left is genuinely unexpected, and is
     * reported with the response body rather than a guessed explanation.
     *
     * Triggered by: RestTemplate calls in OllamaService.
     */
    @ExceptionHandler(HttpClientErrorException.class)
    public ResponseEntity<Map<String, Object>> handleLlmClientError(
            HttpClientErrorException ex) {

        String message = "The local model rejected the request: "
                + ex.getStatusCode() + " | " + ex.getResponseBodyAsString();

        return ResponseEntity
                .status(HttpStatus.BAD_GATEWAY)
                .body(buildError(HttpStatus.BAD_GATEWAY, message));
    }

    /**
     * Handles 5xx responses from the Ollama HTTP API.
     *
     * WHY THERE IS NO LONGER A RETRY BEHIND THIS: against Gemini, a 503 meant
     * "overloaded, try shortly" and OllamaService's predecessor retried three
     * times with backoff before giving up here. A local runtime's 5xx means
     * something went wrong loading or running the model — commonly not enough
     * free VRAM — which retrying does not fix. It surfaces immediately instead
     * of after six seconds of sleeping.
     *
     * Triggered by: RestTemplate calls in OllamaService.
     */
    @ExceptionHandler(HttpServerErrorException.class)
    public ResponseEntity<Map<String, Object>> handleLlmServerError(
            HttpServerErrorException ex) {

        return ResponseEntity
                .status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildError(HttpStatus.SERVICE_UNAVAILABLE,
                        "The local model failed to run the request. This is most often "
                                + "insufficient free VRAM — close other GPU applications and try again."));
    }

    /**
     * Handles illegal/null argument errors.
     *
     * WHY: If something unexpected reaches the service layer with a bad value,
     * we catch it here rather than letting it bubble up as a 500.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(
            IllegalArgumentException ex) {

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(buildError(HttpStatus.BAD_REQUEST, ex.getMessage()));
    }

    /**
     * Catch-all handler — last resort for anything not caught above.
     *
     * WHY: No matter what unexpected exception occurs, the client always
     * gets a structured JSON response, never a raw Spring error page.
     * The generic message avoids leaking internal implementation details
     * (like stack traces or class names) to API consumers.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {

        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildError(HttpStatus.INTERNAL_SERVER_ERROR,
                        "An unexpected error occurred. Please try again."));
    }
}