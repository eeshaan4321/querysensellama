package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.schema.KnownSchema;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;
import java.util.Set;

/** A table's key plus the text column that names its rows. */
final class IdentifyingColumns {

    static final double NAMING_SHARE = 0.9;

    private IdentifyingColumns() {}

    static List<String> of(KnownSchema.Table table, SchemaBinder binder, SchemaMetadata metadata) {
        String key = binder.primaryKey(table);
        Set<String> foreign = binder.foreignKeyColumns(table);
        List<String> chosen = new ArrayList<>(List.of(key));
        for (String column : table.columns()) {
            String type = binder.fieldType(table, column);
            if (column.equals(key) || foreign.contains(column) || column.contains(".")
                    || (type != null && !SchemaBinder.isTextType(type))) {
                continue;
            }
            OptionalDouble share = metadata.distinctShare(table.name(), column);
            if (share.isEmpty() || share.getAsDouble() >= NAMING_SHARE) {
                chosen.add(column);
                break;
            }
        }
        return chosen;
    }
}
