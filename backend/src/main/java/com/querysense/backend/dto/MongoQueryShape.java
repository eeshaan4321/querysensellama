package com.querysense.backend.dto;

import com.querysense.backend.schema.IndexSpec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The fields a MongoDB query matches exactly, sorts on and ranges over, for the index advisor. */
public record MongoQueryShape(
        String collection,
        List<String> equalityFields,
        List<IndexSpec.KeyPart> sortKeys,
        List<String> rangeFields,
        List<String> unanchoredRegexFields,
        boolean groupsWithoutIndexedMatch) {

    public List<IndexSpec.KeyPart> esrKeys() {
        return keys(true);
    }

    public List<IndexSpec.KeyPart> sortIndexKeys() {
        return keys(false);
    }

    private List<IndexSpec.KeyPart> keys(boolean withRange) {
        Map<String, IndexSpec.KeyPart> ordered = new LinkedHashMap<>();
        equalityFields.forEach(f -> ordered.putIfAbsent(f, new IndexSpec.KeyPart(f, 1)));
        sortKeys.forEach(k -> ordered.putIfAbsent(k.column(), k));
        if (withRange) {
            rangeFields.forEach(f -> ordered.putIfAbsent(f, new IndexSpec.KeyPart(f, 1)));
        }
        return List.copyOf(ordered.values());
    }
}
