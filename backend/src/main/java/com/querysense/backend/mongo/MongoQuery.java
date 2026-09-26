package com.querysense.backend.mongo;

import org.bson.Document;

import java.util.List;

/** A mongosh query broken into the parts analysis, rewriting and validation read. */
public record MongoQuery(
        String collection,
        Operation operation,
        Document filter,
        Document projection,
        Document sort,
        Integer limit,
        List<Document> pipeline) {

    public enum Operation { FIND, AGGREGATE, COUNT, DELETE }
}
