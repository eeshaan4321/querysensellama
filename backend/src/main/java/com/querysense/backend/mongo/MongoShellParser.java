package com.querysense.backend.mongo;

import org.bson.Document;
import org.bson.json.JsonParseException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads mongosh query text (find, aggregate, countDocuments, deleteMany) into a MongoQuery. */
@Component
public class MongoShellParser {

    private static final Pattern HEAD = Pattern.compile("^db\\.([A-Za-z_][A-Za-z0-9_]*)\\.([A-Za-z]+)\\(");
    private static final Pattern CHAIN = Pattern.compile("\\s*\\.([A-Za-z]+)\\(");

    public MongoQuery parse(String text) {
        if (text == null || text.isBlank()) {
            throw new MongoShellParseException("The query is empty.", 0);
        }
        String src = text.strip();
        if (src.endsWith(";")) {
            src = src.substring(0, src.length() - 1).stripTrailing();
        }

        Matcher head = HEAD.matcher(src);
        if (!head.find()) {
            throw new MongoShellParseException(
                    "A MongoDB query must start with db.<collection>.<method>(", 0);
        }
        String collection = head.group(1);
        String method = head.group(2);
        int[] pos = {head.end()};
        List<String> args = arguments(src, pos);

        MongoQuery.Operation operation;
        Document filter = new Document();
        Document projection = null;
        List<Document> pipeline = null;
        switch (method) {
            case "find" -> {
                checkMaxArgs(method, args, 2, head.start(2));
                operation = MongoQuery.Operation.FIND;
                filter = args.isEmpty() ? new Document() : document(args.get(0), head.end());
                projection = args.size() > 1 ? document(args.get(1), head.end()) : null;
            }
            case "aggregate" -> {
                checkMaxArgs(method, args, 1, head.start(2));
                operation = MongoQuery.Operation.AGGREGATE;
                pipeline = args.isEmpty() ? List.of() : stages(args.get(0), head.end());
            }
            case "countDocuments", "count" -> {
                checkMaxArgs(method, args, 1, head.start(2));
                operation = MongoQuery.Operation.COUNT;
                filter = args.isEmpty() ? new Document() : document(args.get(0), head.end());
            }
            case "deleteMany" -> {
                checkMaxArgs(method, args, 1, head.start(2));
                operation = MongoQuery.Operation.DELETE;
                filter = args.isEmpty() ? new Document() : document(args.get(0), head.end());
            }
            default -> throw new MongoShellParseException("db." + collection + "." + method
                    + "() is not one of find, aggregate, countDocuments or deleteMany.", head.start(2));
        }

        Document sort = null;
        Integer limit = null;
        while (pos[0] < src.length()) {
            Matcher chained = CHAIN.matcher(src).region(pos[0], src.length());
            if (!chained.lookingAt()) {
                throw new MongoShellParseException(
                        "Unexpected text after the query: \"" + src.substring(pos[0]).strip() + "\"", pos[0]);
            }
            String name = chained.group(1);
            if (operation != MongoQuery.Operation.FIND) {
                throw new MongoShellParseException("." + name + "() can only follow find().", chained.start(1));
            }
            pos[0] = chained.end();
            List<String> chainArgs = arguments(src, pos);
            checkMaxArgs(name, chainArgs, 1, chained.start(1));
            switch (name) {
                case "sort" -> sort = chainArgs.isEmpty() ? new Document() : document(chainArgs.get(0), chained.end());
                case "limit" -> limit = integer(chainArgs, chained.end());
                case "projection" -> projection = chainArgs.isEmpty() ? null : document(chainArgs.get(0), chained.end());
                default -> throw new MongoShellParseException(
                        "." + name + "() is not supported; use sort, limit or projection.", chained.start(1));
            }
        }

        return new MongoQuery(collection, operation, filter, projection, sort, limit, pipeline);
    }

    private List<String> arguments(String src, int[] pos) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int depth = 0;
        char quote = 0;
        for (int i = pos[0]; i < src.length(); i++) {
            char c = src.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && i + 1 < src.length()) {
                    current.append(src.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '{' || c == '[' || c == '(') {
                depth++;
                current.append(c);
            } else if (c == '}' || c == ']') {
                if (depth == 0) {
                    throw new MongoShellParseException("Unmatched '" + c + "'.", i);
                }
                depth--;
                current.append(c);
            } else if (c == ')' && depth > 0) {
                depth--;
                current.append(c);
            } else if (c == ')') {
                addArgument(args, current);
                pos[0] = i + 1;
                return args;
            } else if (c == ',' && depth == 0) {
                addArgument(args, current);
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        throw new MongoShellParseException("The query has no closing ')'.", src.length());
    }

    private static void checkMaxArgs(String method, List<String> args, int max, int position) {
        if (args.size() > max) {
            throw new MongoShellParseException(
                    method + "() takes at most " + max + (max == 1 ? " argument." : " arguments."), position);
        }
    }

    private static void addArgument(List<String> args, StringBuilder current) {
        String arg = current.toString().strip();
        if (!arg.isEmpty()) {
            args.add(arg);
        }
    }

    private static Document document(String json, int position) {
        try {
            return Document.parse(json);
        } catch (JsonParseException | IllegalArgumentException e) {
            throw new MongoShellParseException("Could not read " + json + ": " + e.getMessage(), position);
        }
    }

    private static List<Document> stages(String json, int position) {
        try {
            return Document.parse("{\"p\": " + json + "}").getList("p", Document.class);
        } catch (JsonParseException | IllegalArgumentException | ClassCastException e) {
            throw new MongoShellParseException(
                    "Could not read the pipeline " + json + ": " + e.getMessage(), position);
        }
    }

    private static Integer integer(List<String> args, int position) {
        try {
            return Integer.parseInt(args.get(0).strip());
        } catch (RuntimeException e) {
            throw new MongoShellParseException("limit() needs a whole number.", position);
        }
    }
}
