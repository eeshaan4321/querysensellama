package com.querysense.backend.service.impl;

import com.querysense.backend.exception.LlmUnavailableException;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.service.LlmFallbackService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OllamaService — the LLM fallback (Layer 3 of query generation).
 *
 * Replaces the previous Gemini 2.5 Flash implementation. The hosted API is
 * gone entirely: no API key, no network dependency, no rate limit, no
 * third-party uptime in the critical path of a live demo.
 *
 * WHY LOCAL INFERENCE INSTEAD OF A HOSTED API:
 * 1. The fallback must work offline. A demo that depends on campus wifi plus
 *    a valid GEMINI_API_KEY plus Google's uptime has three failure modes that
 *    a local runtime has zero of.
 * 2. It removes the last secret from the project (CLAUDE.md Section 12's
 *    api-key item is now moot — there is no key to leak).
 * 3. It is more consistent with the architecture QuerySense actually argues
 *    for (Section 2): rule-based, interpretable, no black box the team can't
 *    inspect or version-pin. A pinned local model can be re-run by a reviewer
 *    with the network unplugged.
 *
 * WHY qwen2.5-coder:7b:
 * The fallback only ever sees the HARD inputs — RuleBasedQueryBuilder has
 * already claimed everything simple, so what arrives here is joins,
 * aggregates, ambiguous time expressions, unresolvable projections, and
 * 100% of MongoDB requests. That argues for the strongest code-tuned model
 * that fits. At q4_K_M the weights are ~4.7GB, which leaves real KV-cache
 * headroom inside 8GB of VRAM; a 14B at the same quantisation is ~9GB, does
 * not fit, and would spill to system RAM for roughly a 6x slowdown. So 7B is
 * not a compromise here, it is the ceiling of what the hardware runs fast.
 *
 * WHY NOT a text-to-SQL specialist like sqlcoder: this service has to emit
 * MongoDB as well as MySQL, and a SQL-only specialist follows general
 * formatting instructions poorly. A code-tuned generalist is the right shape
 * for a dual-dialect fallback.
 *
 * HOW OLLAMA'S API DIFFERS FROM GEMINI'S (the parts that actually bite):
 *   - Response is a FLAT {"response": "..."} field, not
 *     candidates[0].content.parts[0].text — the whole nested cast chain the
 *     old implementation needed collapses to a single map lookup.
 *   - "stream" defaults to TRUE and streams newline-delimited JSON objects.
 *     Left at the default, RestTemplate would try to deserialise an NDJSON
 *     body as one Map and fail in a way that looks nothing like the actual
 *     cause. It is set explicitly below and must stay that way.
 *   - There is no thinkingBudget equivalent; that Gemini-specific option was
 *     dropped rather than translated.
 */
@Service
public class OllamaService implements LlmFallbackService {

    @Value("${ollama.api.url}")
    private String apiUrl;

    @Value("${ollama.model}")
    private String model;

    /**
     * How long Ollama keeps the model resident in VRAM after a request.
     *
     * WHY THIS IS NOT LEFT AT THE DEFAULT (5 minutes): loading ~4.7GB from
     * disk into VRAM costs 2-4 seconds, and it is paid by whichever request
     * arrives after an idle gap — which, during a panel demo, is exactly the
     * query someone is watching. A long keep_alive plus the startup warm-up
     * (see warmUp()) means that cost is paid once, before anyone is looking.
     */
    @Value("${ollama.keep-alive:30m}")
    private String keepAlive;

    /**
     * Hard ceiling on generated tokens.
     *
     * WHY: a 7B model follows "return ONLY the query, no explanation" less
     * reliably than Gemini did. Without a cap, an otherwise-correct 60-token
     * query followed by 250 tokens of unwanted commentary turns a ~1 second
     * response into ~4 seconds. The cap bounds the worst case; cleanResponse()
     * below discards the commentary itself.
     */
    @Value("${ollama.num-predict:300}")
    private int numPredict;

    private final RestTemplate restTemplate;
    private final ActiveSchemaProvider activeSchema;

    public OllamaService(RestTemplate restTemplate, ActiveSchemaProvider activeSchema) {
        this.restTemplate = restTemplate;
        this.activeSchema = activeSchema;
    }

    // -----------------------------------------------------------------------
    // GENERATION
    // -----------------------------------------------------------------------

    @Override
    public String generateQuery(String userInput, String databaseType) {
        String dialect = resolveDialect(databaseType);
        String prompt = buildPrompt(userInput, dialect);

        Map<String, Object> body = requestBody(prompt, numPredict);
        String raw = post(body);

        return cleanResponse(raw, dialect);
    }

    /**
     * Builds the Ollama /api/generate request body.
     *
     * WHY temperature 0 (Gemini was left at its ~1.0 default):
     * Query generation is not a creative task. The same natural language
     * input should produce the same query every time, or the before/after
     * comparisons this project is built around aren't reproducible. Combined
     * with a fixed seed this makes a demo re-runnable and a reported result
     * checkable by a reviewer — which is the whole point of preferring
     * interpretable components (CLAUDE.md Section 2).
     *
     * WHY NO "stop" SEQUENCES, despite them being the obvious way to cut off
     * trailing commentary: the tempting stop token is "```", and it is a trap.
     * When the model wraps its answer in a fenced block, the OPENING fence is
     * the first thing it emits — so a stop on "```" truncates the response to
     * an empty string before any query is generated at all. Every other
     * candidate ("\n\n", "Note:") risks cutting a legitimate multi-line
     * MongoDB pipeline. Trimming after the fact in cleanResponse() cannot
     * destroy output that was already produced; a stop sequence can.
     */
    private Map<String, Object> requestBody(String prompt, int predictTokens) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0);
        options.put("seed", 42);
        options.put("num_predict", predictTokens);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);   // see class Javadoc — must not be removed
        body.put("keep_alive", keepAlive);
        body.put("options", options);
        return body;
    }

    /**
     * Posts to Ollama and extracts the generated text.
     *
     * WHY NO RETRY LOOP (the Gemini implementation retried 3x with 2s/4s
     * backoff): that was correct for a hosted API, where a 503 means
     * "overloaded, try shortly" and a retry genuinely succeeds. The failure
     * modes of a local runtime are different in kind — the server isn't
     * running, or the model isn't pulled — and both are permanent for the
     * lifetime of the request. Retrying them adds six seconds of sleeping to
     * an outcome that was already decided, and Thread.sleep() on a request
     * thread is not a pattern worth carrying forward. A slow cold start is
     * not an error and is covered by the read timeout in AppConfig instead.
     */
    @SuppressWarnings("unchecked")
    private String post(Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            ResponseEntity<Map> response =
                    restTemplate.postForEntity(apiUrl, new HttpEntity<>(body, headers), Map.class);

            Map<String, Object> payload = response.getBody();
            if (payload == null || payload.get("response") == null) {
                throw new LlmUnavailableException(
                        "Ollama returned a response with no generated text. "
                                + "This usually means 'stream' was not set to false.");
            }
            return (String) payload.get("response");

        } catch (ResourceAccessException e) {
            // Connection refused, or the read timeout elapsed.
            throw new LlmUnavailableException(
                    "Cannot reach Ollama at " + apiUrl + ". Start it (the Ollama desktop app, "
                            + "or `ollama serve`) and try again.", e);

        } catch (HttpClientErrorException.NotFound e) {
            // Ollama answers 404 when the requested model isn't installed.
            throw new LlmUnavailableException(
                    "The model '" + model + "' is not installed. Run: ollama pull " + model, e);
        }
    }

    // -----------------------------------------------------------------------
    // PROMPT
    // -----------------------------------------------------------------------

    /**
     * Carried over from the Gemini implementation, with the constraints
     * tightened.
     *
     * WHY TIGHTENED RATHER THAN COPIED VERBATIM: the safety and correctness
     * rules (no DROP, no unqualified DELETE, ONLY_FULL_GROUP_BY compliance)
     * are dialect facts and transfer unchanged. The FORMATTING rules do not —
     * a 7B model needs the "only the query" instruction stated more bluntly
     * and more than once, because it is the instruction it is most likely to
     * ignore. cleanResponse() is the backstop for when it ignores it anyway.
     */
    private String buildPrompt(String userInput, String dialect) {
        String schemaContext = buildSchemaContext(userInput, dialect);

        return String.format("""
            You are a database expert. Convert the following natural language query
            into a valid %s query.

            Rules:
            - Output ONLY the query text. No explanation, no commentary, no markdown,
              no code fences, no leading phrase such as "Here is the query".
            - Your entire reply must be the query and nothing else.
            - Use proper %s syntax.
            - Never generate DROP TABLE or DROP DATABASE statements.
            - Never generate DELETE without a WHERE clause.
            - If this query needs GROUP BY or HAVING, every selected column that is NOT wrapped in an
              aggregate function (COUNT, SUM, AVG, MIN, MAX, etc.) must also appear in the GROUP BY
              clause — MySQL's ONLY_FULL_GROUP_BY strict mode (the default) rejects anything else.
            - If the request is ambiguous, generate the safest reasonable interpretation.
            - End every SQL statement with a semicolon.
            %s
            Natural language input: %s

            Query:
            """,
                dialect, dialect, schemaContext, userInput
        );
    }

    /**
     * Builds a "here is the real schema" block for tables the input appears
     * to reference, so the model is grounded in actual column names — and,
     * when a request implies a relationship (e.g. "customers ... with orders
     * more than 3"), the real foreign key between them — instead of guessing.
     * Empty (no-op) when the dialect isn't MySQL, or when no known table is
     * mentioned.
     *
     * WHY THIS IS THE MOST IMPORTANT METHOD IN THE CLASS NOW:
     * Schema grounding is the single largest accuracy lever for a small local
     * model on text-to-SQL — it matters considerably more at 7B than it did
     * against Gemini, because a smaller model has far less capacity to guess a
     * plausible schema correctly. Bug 9 (the join condition Gemini silently
     * dropped) was partly caused by the prompt never mentioning `orders` or
     * the customers/orders relationship at all; that failure would be more
     * frequent, not less, without this block.
     *
     * The MongoDB path deliberately gets none of this, because KnownSchema
     * describes relational tables. That is a real, acknowledged asymmetry —
     * the Mongo fallback is both unguarded (see GeneratedQueryValidator) and
     * ungrounded, and should be evaluated as its own case.
     */
    private String buildSchemaContext(String userInput, String dialect) {
        if (!"MySQL".equalsIgnoreCase(dialect)) {
            return "";
        }

        // WHY AN UNMATCHED QUESTION NOW GETS THE WHOLE SCHEMA RATHER THAN NONE:
        // This used to return "" when the question named no known table, so
        // the model got NO schema at all for exactly the questions most
        // likely to hallucinate - ones phrased in business words ("buyers",
        // "spend") rather than schema words. PROJECT.md records the result:
        // a confident SELECT against a `transactions` table that does not
        // exist, which the cache then served back at confidence 1.0. An
        // over-broad prompt is strictly better than an empty one.
        //
        // This closes fix direction 1 of that defect. Directions 2 (a
        // schema-conformance check before a result is returned or cached)
        // and 3 (MongoDB grounding) remain open.
        List<KnownSchema.Table> mentioned = activeSchema.detectTablesMentioned(userInput);
        List<KnownSchema.Table> tables = mentioned.isEmpty() ? activeSchema.tables() : mentioned;
        if (tables.isEmpty()) {
            return "";
        }

        StringBuilder schema = new StringBuilder(
                "\n            Real schema — use ONLY these tables and columns; never invent a column or table name:\n");
        for (KnownSchema.Table table : tables) {
            schema.append("            - ").append(table.name())
                    .append("(").append(String.join(", ", table.columns())).append(")\n");
        }

        for (KnownSchema.ForeignKey fk : activeSchema.foreignKeys()) {
            boolean fromMentioned = tables.stream().anyMatch(t -> t.name().equals(fk.fromTable()));
            boolean toMentioned = tables.stream().anyMatch(t -> t.name().equals(fk.toTable()));
            if (fromMentioned && toMentioned) {
                schema.append("            - Relationship: ").append(fk.fromTable()).append(".")
                        .append(fk.fromColumn()).append(" references ").append(fk.toTable()).append(".")
                        .append(fk.toColumn())
                        .append(" — use this to JOIN/GROUP BY/HAVING when the request implies it ")
                        .append("(e.g. \"customers with orders more than N\", \"who placed more than N orders\").\n");
            }
        }
        return schema.toString();
    }

    private String resolveDialect(String databaseType) {
        return (databaseType != null && !databaseType.isBlank()) ? databaseType : "MySQL";
    }

    // -----------------------------------------------------------------------
    // RESPONSE CLEANING
    // -----------------------------------------------------------------------
    // The old implementation stripped exactly two things: a literal "```sql"
    // fence and a bare "```". That was adequate for Gemini. It is not adequate
    // here — a 7B model wraps output in ```mysql, ```javascript and ```json
    // fences, prefixes replies with "Here is the query:", and appends an
    // explanatory paragraph despite being told three times not to. None of
    // that is a model defect to be fixed by more prompting; it is the ordinary
    // integration cost of a smaller model, and it belongs in code.

    /** Any fenced block, whatever language tag it carries. Group 1 is its contents. */
    private static final Pattern FENCED_BLOCK =
            Pattern.compile("```[a-zA-Z]*\\s*\\r?\\n?(.*?)```", Pattern.DOTALL);

    /** An unterminated/stray fence marker left over when there is no matched pair. */
    private static final Pattern STRAY_FENCE = Pattern.compile("```[a-zA-Z]*");

    /**
     * A conversational lead-in line.
     *
     * WHY IT REQUIRES A TRAILING COLON: that is what makes this safe. A line
     * of SQL or a MongoDB expression never ends in a colon, so this pattern
     * cannot match a line of real output no matter what it starts with.
     */
    private static final Pattern LEAD_IN_LINE = Pattern.compile(
            "(?i)^\\s*(here('s| is)?|sure|certainly|okay|of course|the following|this)\\b[^\\n]*:\\s*$");

    /**
     * Reduces a raw model reply to just the query.
     *
     * Order matters: unwrap the fence first (if the model fenced its answer,
     * the fence contents ARE the answer and any prose outside it is noise),
     * then drop conversational lead-in lines, then — for SQL only — cut
     * everything after the first statement terminator.
     */
    private String cleanResponse(String raw, String dialect) {
        if (raw == null) {
            return "";
        }
        String text = raw.trim();

        Matcher fence = FENCED_BLOCK.matcher(text);
        if (fence.find()) {
            text = fence.group(1).trim();
        } else {
            text = STRAY_FENCE.matcher(text).replaceAll("").trim();
        }

        text = stripLeadInLines(text);

        // SQL only: the prompt requires a semicolon terminator, so anything
        // past the first one is commentary the model added anyway. MongoDB is
        // excluded because its queries are brace-delimited expressions with no
        // equivalent terminator, and a stray semicolon inside one would be a
        // far worse thing to cut on.
        if (!"MongoDB".equalsIgnoreCase(dialect)) {
            int terminator = text.indexOf(';');
            if (terminator >= 0) {
                text = text.substring(0, terminator + 1);
            }
        }

        return text.trim();
    }

    private String stripLeadInLines(String text) {
        String[] lines = text.split("\\r?\\n");
        int start = 0;
        while (start < lines.length
                && (lines[start].isBlank() || LEAD_IN_LINE.matcher(lines[start]).matches())) {
            start++;
        }
        return String.join("\n", List.of(lines).subList(start, lines.length)).trim();
    }

    // -----------------------------------------------------------------------
    // WARM-UP
    // -----------------------------------------------------------------------

    /**
     * Sends a one-token request purely to force the model into VRAM.
     *
     * Called once at startup by LlmWarmUpRunner. Deliberately swallows every
     * failure: if Ollama isn't running, that is a problem for the first real
     * fallback request to report with a useful message — it must not stop the
     * Spring context from coming up, since the rule engine, analysis, schema
     * advisor and history modules all work perfectly well without any LLM at
     * all. That is the entire point of an LLM-as-fallback architecture, and it
     * would be undermined by a startup that depends on the fallback.
     */
    @Override
    public void warmUp() {
        try {
            post(requestBody("SELECT 1;", 1));
        } catch (RuntimeException e) {
            // Intentionally ignored — see method Javadoc.
        }
    }
}
