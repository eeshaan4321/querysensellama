package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * GeneratedQueryValidator
 *
 * WHY THIS IS ITS OWN CLASS NOW (extracted from GeminiService):
 * This logic used to live inside GeminiService as looksIncomplete(), but it
 * has nothing to do with talking to a model. It compares the user's natural
 * language input against the query text that came back, using only
 * KnownSchema and two regexes — it never touches the network and doesn't
 * care which provider produced the query. Leaving it inside the provider
 * class meant that swapping Gemini for Ollama would have either dragged it
 * along or duplicated it. It belongs beside RuleBasedQueryBuilder, with the
 * rest of the generation pipeline's reasoning about query correctness.
 *
 * WHY IT MATTERS MORE NOW THAN IT DID BEFORE:
 * This check was written against Gemini 2.5 Flash. The fallback now runs a
 * 7B model locally, and the fallback only ever sees the *hard* inputs — the
 * rule engine has already claimed everything simple, so what reaches the LLM
 * is joins, aggregates, ambiguous time expressions and all MongoDB requests
 * (see RuleBasedQueryBuilder's six bail-out conditions). A smaller model
 * dropping one condition of a multi-condition request is more likely, not
 * less, so this guard is now load-bearing rather than belt-and-braces.
 *
 * -----------------------------------------------------------------------
 * INCOMPLETE-GENERATION DETECTION (originally Bug 9)
 * -----------------------------------------------------------------------
 * Prompt-grounding reduces but doesn't eliminate the model dropping part of
 * a multi-condition request (e.g. generating a plain WHERE city = '...' for
 * "customers in mumbai with orders more than 3", silently losing the
 * orders-related condition entirely). Rather than trust the model
 * unconditionally, this is a lightweight sanity check with two signals:
 *
 * 1. SCHEMA-DRIVEN (general, catches the actual repro): if the input
 *    mentions more than one KnownSchema table (e.g. both "customer" and
 *    "order" forms — "total order amount for each customer" counts, same as
 *    "customers ... orders" does), that almost always implies a JOIN. If the
 *    generated SQL doesn't reference all of those tables, something was
 *    dropped — this doesn't depend on guessing which verb or number-word the
 *    request happened to use.
 * 2. PHRASE-BASED (narrower fallback): relational language ("with", "who
 *    have/placed/has") plus a quantitative/comparison cue implies a
 *    HAVING/GROUP BY-shaped condition even in the rarer case where only one
 *    table is named outright and the model is expected to infer the join on
 *    its own.
 *
 * Either signal firing lowers the reported confidence in
 * QueryGenerationServiceImpl instead of silently returning a
 * confidently-labeled partial answer.
 *
 * KNOWN LIMITATION, STATED HONESTLY: both signals are MySQL-only. MongoDB
 * generation currently has no equivalent guard, and since 100% of MongoDB
 * generation goes through the LLM fallback (RuleBasedQueryBuilder defers all
 * Mongo requests until Task B exists), the Mongo path is the least protected
 * part of the pipeline. Worth reporting Mongo accuracy separately rather
 * than folding it into one blended figure.
 */
@Component
public class GeneratedQueryValidator {

    private static final Pattern MULTI_CONDITION_LANGUAGE = Pattern.compile(
            ".*\\b(with|who\\s+have|who\\s+placed|who\\s+has|and)\\b.*"
    );
    private static final Pattern QUANTITATIVE_LANGUAGE = Pattern.compile(
            ".*(\\d+|more\\s+than|less\\s+than|greater\\s+than|at\\s+least|at\\s+most|over|under)\\b.*"
    );

    /**
     * @return true if the generated query looks like it dropped part of what
     *         the input asked for. A true here is a confidence signal, not a
     *         rejection — the query is still returned, just labeled lower.
     */
    public boolean looksIncomplete(String userInput, String generatedQuery, String databaseType) {
        if (!"MySQL".equalsIgnoreCase(databaseType) || userInput == null || generatedQuery == null) {
            return false;
        }

        String sql = generatedQuery.toLowerCase();

        List<KnownSchema.Table> mentionedTables = KnownSchema.detectTablesMentioned(userInput);
        if (mentionedTables.size() > 1) {
            boolean sqlReferencesEveryMentionedTable = mentionedTables.stream()
                    .allMatch(t -> sql.contains(t.name()) || sql.contains(t.singularName()));
            if (!sqlReferencesEveryMentionedTable) {
                return true;
            }
        }

        String input = userInput.toLowerCase();
        boolean impliesMultipleConditions =
                MULTI_CONDITION_LANGUAGE.matcher(input).matches() && QUANTITATIVE_LANGUAGE.matcher(input).matches();
        if (impliesMultipleConditions) {
            boolean showsComparableComplexity =
                    sql.contains("join") || sql.contains("having") || sql.contains("group by") || sql.contains(" and ");
            return !showsComparableComplexity;
        }

        return false;
    }
}
