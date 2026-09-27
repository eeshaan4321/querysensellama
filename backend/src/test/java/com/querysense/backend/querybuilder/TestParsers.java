package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.schema.ActiveSchemaProvider;

import java.util.List;

final class TestParsers {

    private TestParsers() {}

    static QueryIntentParser parser(ActiveSchemaProvider schemas, SchemaMetadata mysql, SchemaMetadata mongo) {
        return new QueryIntentParser(
                new SchemaBinder(schemas),
                new JoinPathResolver(schemas),
                List.of(mysql, mongo),
                List.of(new SqlEmitter(), new MongoEmitter(schemas)));
    }
}
