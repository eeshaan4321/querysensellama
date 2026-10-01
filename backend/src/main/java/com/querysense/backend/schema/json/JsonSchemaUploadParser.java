package com.querysense.backend.schema.json;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.exception.SchemaUploadException;
import com.querysense.backend.schema.IndexSpec;
import com.querysense.backend.schema.KnownSchema;
import com.querysense.backend.schema.ddl.ParsedColumn;
import com.querysense.backend.schema.ddl.ParsedSchema;
import com.querysense.backend.schema.ddl.ParsedTable;
import com.querysense.backend.schema.upload.SchemaUploadParser;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Parses an uploaded MongoDB schema: collections with $jsonSchema validators, indexes and references. */
@Component
public class JsonSchemaUploadParser implements SchemaUploadParser {

    public static final Set<String> GENERATED_TYPES = Set.of(
            "string", "int", "long", "double", "decimal", "bool", "date", "objectId",
            "number", "object", "array", "any");

    private static final List<String> UNREAD_KEYWORDS = List.of(
            "oneOf", "anyOf", "allOf", "not", "patternProperties", "$ref", "dependencies");
    private static final List<String> IGNORED_CONSTRAINTS = List.of(
            "pattern", "minLength", "minimum", "maximum", "exclusiveMinimum", "exclusiveMaximum",
            "multipleOf", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties");

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)
            .enable(JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .enable(JsonReadFeature.ALLOW_TRAILING_COMMA)
            .build();

    private record Collection(ParsedTable table, List<IndexSpec> indexes) {
    }

    @Override
    public Dialect dialect() {
        return Dialect.MONGODB;
    }

    @Override
    public ParsedSchema parse(String text) {
        JsonNode root = read(text);
        List<String> warnings = new ArrayList<>();
        List<ParsedTable> tables = new ArrayList<>();
        List<IndexSpec> indexes = new ArrayList<>();
        for (JsonNode entry : collectionEntries(root)) {
            Collection parsed = parseCollection(entry, warnings);
            if (parsed != null) {
                tables.add(parsed.table());
                indexes.addAll(parsed.indexes());
            }
        }
        return new ParsedSchema(List.copyOf(tables), List.copyOf(warnings), List.copyOf(indexes));
    }

    public static String normalise(String type) {
        return switch (type) {
            case "boolean" -> "bool";
            case "integer" -> "int";
            default -> type;
        };
    }

    static String typeOf(JsonNode node) {
        JsonNode declared = node.has("bsonType") ? node.get("bsonType") : node.get("type");
        if (declared != null && declared.isTextual()) {
            return normalise(declared.asText());
        }
        if (declared != null && declared.isArray()) {
            for (JsonNode t : declared) {
                if (t.isTextual() && !"null".equals(t.asText())) {
                    return normalise(t.asText());
                }
            }
        }
        if (node.has("properties")) {
            return "object";
        }
        if (node.has("items")) {
            return "array";
        }
        return "any";
    }

    private JsonNode read(String text) {
        try {
            JsonNode root = MAPPER.readTree(text);
            if (root == null || root.isMissingNode()) {
                throw new SchemaUploadException(
                        "The uploaded schema was empty. Upload a .json file describing the collections.");
            }
            return root;
        } catch (JsonProcessingException e) {
            JsonLocation at = e.getLocation();
            String where = at == null ? "" : " at line " + at.getLineNr() + ", column " + at.getColumnNr();
            throw new SchemaUploadException(
                    "The uploaded file is not valid JSON" + where + ": " + e.getOriginalMessage(), e);
        }
    }

    private Iterable<JsonNode> collectionEntries(JsonNode root) {
        if (root.isArray()) {
            return root;
        }
        if (root.isObject() && root.path("collections").isArray()) {
            return root.get("collections");
        }
        throw new SchemaUploadException(
                "Expected a JSON object with a \"collections\" array, or the array that "
                        + "db.getCollectionInfos() returns.");
    }

    private Collection parseCollection(JsonNode entry, List<String> warnings) {
        JsonNode nameNode = entry.path("name");
        if (!nameNode.isTextual() || nameNode.asText().isBlank()) {
            throw new SchemaUploadException("Every collection in the upload needs a \"name\".");
        }
        String name = nameNode.asText();
        if (name.startsWith("system.")) {
            warnings.add("Skipped '" + name + "': system collections are not uploaded.");
            return null;
        }
        if ("view".equals(entry.path("type").asText())) {
            warnings.add("Skipped '" + name + "': it is a view, and QuerySense uploads collections only.");
            return null;
        }

        JsonNode validator = entry.has("validator") ? entry.get("validator") : entry.path("options").path("validator");
        JsonNode jsonSchema = validator.path("$jsonSchema");
        if (validator.isObject() && validator.size() > (jsonSchema.isMissingNode() ? 0 : 1)) {
            warnings.add("The validator of '" + name + "' has query operators besides $jsonSchema. They are "
                    + "provisioned, but QuerySense reads fields from $jsonSchema only.");
        }

        List<IndexSpec> indexes = parseIndexes(name, entry.path("indexes"), warnings);
        Set<String> uniquePaths = indexes.stream()
                .filter(IndexSpec::unique)
                .filter(i -> i.keys().size() == 1)
                .map(i -> i.keys().get(0).column())
                .collect(Collectors.toSet());

        List<ParsedColumn> columns = new ArrayList<>();
        List<String> embeddedArrays = new ArrayList<>();
        JsonNode idNode = jsonSchema.path("properties").path("_id");
        columns.add(new ParsedColumn("_id", idNode.isMissingNode() ? "objectId" : typeOf(idNode),
                List.of(), true, false, true, true));
        if (jsonSchema.isMissingNode()) {
            warnings.add("'" + name + "' has no $jsonSchema validator, so only _id is known about its documents.");
        } else {
            walk(name, "", jsonSchema, columns, embeddedArrays, uniquePaths, warnings);
        }

        Document validatorDocument = validator.isObject() ? Document.parse(validator.toString()) : null;
        ParsedTable table = new ParsedTable(name, List.copyOf(columns), parseReferences(name, entry.path("references")),
                List.copyOf(embeddedArrays), validatorDocument);
        return new Collection(table, indexes);
    }

    private void walk(String collection, String prefix, JsonNode schema, List<ParsedColumn> columns,
                      List<String> embeddedArrays, Set<String> uniquePaths, List<String> warnings) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(r -> required.add(r.asText()));
        warnUnread(collection, prefix.isEmpty() ? "(document)" : prefix, schema, warnings);

        for (Map.Entry<String, JsonNode> field : schema.path("properties").properties()) {
            if (prefix.isEmpty() && "_id".equals(field.getKey())) {
                continue;
            }
            String path = prefix.isEmpty() ? field.getKey() : prefix + "." + field.getKey();
            JsonNode node = field.getValue();
            String type = typeOf(node);
            boolean isRequired = required.contains(field.getKey());

            if ("object".equals(type) && node.has("properties")) {
                walk(collection, path, node, columns, embeddedArrays, uniquePaths, warnings);
            } else if ("array".equals(type)) {
                embeddedArrays.add(path);
                JsonNode items = node.path("items");
                String itemType = items.isObject() ? typeOf(items) : "any";
                if ("object".equals(itemType) && items.has("properties")) {
                    walk(collection, path, items, columns, embeddedArrays, uniquePaths, warnings);
                } else {
                    warnUnread(collection, path, items.isObject() ? items : node, warnings);
                    addLeaf(collection, path, items.isObject() ? items : node, itemType, isRequired,
                            columns, uniquePaths, warnings);
                }
            } else {
                warnUnread(collection, path, node, warnings);
                addLeaf(collection, path, node, type, isRequired, columns, uniquePaths, warnings);
            }
        }
    }

    private void addLeaf(String collection, String path, JsonNode node, String type, boolean required,
                         List<ParsedColumn> columns, Set<String> uniquePaths, List<String> warnings) {
        if (!GENERATED_TYPES.contains(type) && !required) {
            warnings.add("Field '" + collection + "." + path + "' has bsonType '" + type + "', which QuerySense "
                    + "cannot generate values for, so seeded documents leave it out.");
        }
        List<String> args = node.path("maxLength").canConvertToInt()
                ? List.of(node.get("maxLength").asText())
                : List.of();
        columns.add(new ParsedColumn(path, type, args, required, false, uniquePaths.contains(path), false));
    }

    private void warnUnread(String collection, String path, JsonNode node, List<String> warnings) {
        for (String keyword : UNREAD_KEYWORDS) {
            if (node.has(keyword)) {
                warnings.add("'" + collection + "' uses " + keyword + " at " + path
                        + ", which QuerySense does not read; those alternatives are ignored.");
            }
        }
        for (String keyword : IGNORED_CONSTRAINTS) {
            if (node.has(keyword)) {
                warnings.add("Field '" + collection + "." + path + "' sets " + keyword + ". Seeded values do not "
                        + "follow it, so MongoDB's validator may reject them.");
            }
        }
    }

    private List<IndexSpec> parseIndexes(String collection, JsonNode indexes, List<String> warnings) {
        List<IndexSpec> out = new ArrayList<>();
        for (JsonNode index : indexes) {
            JsonNode key = index.path("key");
            if (!key.isObject() || key.isEmpty()) {
                warnings.add("Skipped an index on '" + collection + "' that has no \"key\".");
                continue;
            }
            List<IndexSpec.KeyPart> parts = new ArrayList<>();
            boolean plain = true;
            for (Map.Entry<String, JsonNode> part : key.properties()) {
                if (!part.getValue().isNumber()) {
                    plain = false;
                    break;
                }
                parts.add(new IndexSpec.KeyPart(part.getKey(), part.getValue().asDouble() < 0 ? -1 : 1));
            }
            if (!plain) {
                warnings.add("Skipped the index " + key + " on '" + collection + "': only ascending (1) and "
                        + "descending (-1) keys are provisioned.");
                continue;
            }
            if (parts.size() == 1 && "_id".equals(parts.get(0).column())) {
                continue;
            }
            out.add(new IndexSpec(collection, List.copyOf(parts), index.path("unique").asBoolean(false)));
        }
        return out;
    }

    private List<KnownSchema.ForeignKey> parseReferences(String collection, JsonNode references) {
        List<KnownSchema.ForeignKey> out = new ArrayList<>();
        for (Map.Entry<String, JsonNode> reference : references.properties()) {
            String target = reference.getValue().asText("");
            int dot = target.indexOf('.');
            if (!reference.getValue().isTextual() || dot <= 0 || dot == target.length() - 1) {
                throw new SchemaUploadException("The reference " + collection + "." + reference.getKey()
                        + " must name a collection and a field, like \"customers._id\".");
            }
            out.add(new KnownSchema.ForeignKey(collection, reference.getKey(),
                    target.substring(0, dot), target.substring(dot + 1)));
        }
        return List.copyOf(out);
    }
}
