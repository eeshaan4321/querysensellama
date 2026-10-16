package com.querysense.backend.analysis;

import com.querysense.backend.dialect.DialectAware;

import java.util.List;
import java.util.OptionalDouble;

/** Live column names and stored values for one dialect's active schema. */
public interface SchemaMetadata extends DialectAware {
    List<String> getColumnNames(String tableName);

    List<String> getDistinctValues(String tableName, String columnName);

    default OptionalDouble distinctShare(String tableName, String columnName) {
        return OptionalDouble.empty();
    }
}
