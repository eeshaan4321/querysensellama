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

/** LLM fallback backed by a local Ollama model. */
@Service
public class OllamaService implements LlmFallbackService {

    @Value("${ollama.api.url}")
    private String apiUrl;

    @Value("${ollama.model}")
    private String model;

    @Value("${ollama.keep-alive:30m}")
    private String keepAlive;

    @Value("${ollama.num-predict:300}")
    private int numPredict;

    private final RestTemplate restTemplate;
    private final ActiveSchemaProvider activeSchema;

    public OllamaService(RestTemplate restTemplate, ActiveSchemaProvider activeSchema) {
        this.restTemplate = restTemplate;
        this.activeSchema = activeSchema;
    }

    @Override
    public String generateQuery(String userInput, String databaseType) {
        String dialect = resolveDialect(databaseType);
        String prompt = buildPrompt(userInput, dialect);

        Map<String, Object> body = requestBody(prompt, numPredict);
        String raw = post(body);

        return cleanResponse(raw, dialect);
    }

    private Map<String, Object> requestBody(String prompt, int predictTokens) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", 0);
        options.put("seed", 42);
        options.put("num_predict", predictTokens);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("prompt", prompt);
        body.put("stream", false);
        body.put("keep_alive", keepAlive);
        body.put("options", options);
        return body;
    }

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
            throw new LlmUnavailableException(
                    "Cannot reach Ollama at " + apiUrl + ". Start it (the Ollama desktop app, "
                            + "or `ollama serve`) and try again.", e);

        } catch (HttpClientErrorException.NotFound e) {
            throw new LlmUnavailableException(
                    "The model '" + model + "' is not installed. Run: ollama pull " + model, e);
        }
    }

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

    private String buildSchemaContext(String userInput, String dialect) {
        if (!"MySQL".equalsIgnoreCase(dialect)) {
            return "";
        }

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

    private static final Pattern FENCED_BLOCK =
            Pattern.compile("```[a-zA-Z]*\\s*\\r?\\n?(.*?)```", Pattern.DOTALL);

    private static final Pattern STRAY_FENCE = Pattern.compile("```[a-zA-Z]*");

    private static final Pattern LEAD_IN_LINE = Pattern.compile(
            "(?i)^\\s*(here('s| is)?|sure|certainly|okay|of course|the following|this)\\b[^\\n]*:\\s*$");

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

    @Override
    public void warmUp() {
        try {
            post(requestBody("SELECT 1;", 1));
        } catch (RuntimeException e) {
        }
    }
}
