package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Flags LLM-generated SQL that appears to miss part of the question. */
@Component
public class GeneratedQueryValidator {

    private final ActiveSchemaProvider activeSchema;

    public GeneratedQueryValidator(ActiveSchemaProvider activeSchema) {
        this.activeSchema = activeSchema;
    }

    private static final Pattern MULTI_CONDITION_LANGUAGE = Pattern.compile(
            ".*\\b(with|who\\s+have|who\\s+placed|who\\s+has|and)\\b.*"
    );
    private static final Pattern QUANTITATIVE_LANGUAGE = Pattern.compile(
            ".*(\\d+|more\\s+than|less\\s+than|greater\\s+than|at\\s+least|at\\s+most|over|under)\\b.*"
    );

    public boolean looksIncomplete(String userInput, String generatedQuery, String databaseType) {
        if (!"MySQL".equalsIgnoreCase(databaseType) || userInput == null || generatedQuery == null) {
            return false;
        }

        String sql = generatedQuery.toLowerCase();

        List<KnownSchema.Table> mentionedTables = activeSchema.detectTablesMentioned(userInput);
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
