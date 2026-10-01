package com.querysense.backend.analysis;

import com.querysense.backend.mongo.MongoQuery;
import com.querysense.backend.mongo.MongoShellParser;
import org.bson.Document;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Reads explain and listIndexes output captured from a real mongod by MongoExplainFixtureCapture. */
final class MongoFixtures {

    record Captured(MongoQuery query, Document explain) {
    }

    private MongoFixtures() {
    }

    static Captured explain(String name) {
        Document file = Document.parse(read("mongo/explain/" + name + ".json"));
        return new Captured(new MongoShellParser().parse(file.getString("query")),
                file.get("explain", Document.class));
    }

    static List<Document> indexes(String collection) {
        return Document.parse(read("mongo/listIndexes/demo.json")).getList(collection, Document.class);
    }

    private static String read(String path) {
        try {
            return new ClassPathResource(path).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
