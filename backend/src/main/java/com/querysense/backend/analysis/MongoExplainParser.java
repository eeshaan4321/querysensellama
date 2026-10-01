package com.querysense.backend.analysis;

import com.querysense.backend.dto.PlanNode;
import com.querysense.backend.dto.QueryAnalysisResult;
import com.querysense.backend.dto.TableAnalysis;
import com.querysense.backend.mongo.MongoQuery;
import org.bson.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Maps MongoDB explain output, at executionStats verbosity, onto a QueryAnalysisResult. */
@Component
public class MongoExplainParser {

    private static final String UNREADABLE = "MongoDB returned an explain result in a shape QuerySense does not "
            + "read, so no plan figures are shown. The query itself was not run.";

    private static final class Walk {
        final List<TableAnalysis> tables = new ArrayList<>();
        final Set<String> unknownStages = new LinkedHashSet<>();
        boolean withStats;
        boolean sorts;
        boolean lookup;
        String collection;
    }

    public void read(Document explain, MongoQuery query, QueryAnalysisResult result) {
        Document source = explain;
        List<Document> laterStages = List.of();
        if (explain.get("stages") instanceof List<?> stages && !stages.isEmpty()) {
            source = stages.get(0) instanceof Document first && first.get("$cursor") instanceof Document cursor
                    ? cursor
                    : null;
            laterStages = stages.subList(1, stages.size()).stream()
                    .filter(Document.class::isInstance).map(Document.class::cast).toList();
        }
        Document planner = documentAt(source, "queryPlanner");
        Document stats = documentAt(source, "executionStats");
        Document winning = documentAt(planner, "winningPlan");
        if (winning == null || stats == null) {
            fail(result);
            return;
        }

        Document queryPlan = documentAt(winning, "queryPlan");
        Document planRoot = queryPlan != null ? queryPlan : winning;
        if ("EOF".equals(stageOf(planRoot)) && "nonExistentNamespace".equals(planRoot.get("type"))) {
            result.addWarning("The collection '" + query.collection() + "' does not exist in MongoDB yet, so "
                    + "there is no plan to read. If this is the built-in demo, start the backend with mongod "
                    + "running so it can be seeded, then analyze the query again.");
            result.setExplainFailed(true);
            return;
        }

        Walk walk = new Walk();
        walk.collection = query.collection();
        Document executionStages = documentAt(stats, "executionStages");
        walk.withStats = queryPlan == null && executionStages != null;
        Document root = walk.withStats ? executionStages : planRoot;
        if (stageOf(root) == null) {
            fail(result);
            return;
        }

        PlanNode tree = node(root, null, walk);
        for (Document stage : laterStages) {
            String name = stage.keySet().stream().filter(k -> k.startsWith("$")).findFirst().orElse("?");
            boolean sorts = "$sort".equals(name);
            walk.sorts |= sorts;
            tree = new PlanNode(name, null, null, null, null, null, sorts, spilledHere(stage), List.of(tree));
        }

        markPossibleKeys(planner, walk);
        if (!walk.withStats && !walk.lookup
                && walk.tables.stream().map(TableAnalysis::getTableName).distinct().count() == 1) {
            walk.tables.forEach(t -> t.setEstimatedRows(number(stats.get("totalDocsExamined"))));
        }

        summarise(result, query, stats, walk, tree, spilledAnywhere(stats) || spilledAnywhere(laterStages));
    }

    private PlanNode node(Document stage, Document parent, Walk walk) {
        String name = stageOf(stage);
        if (name != null && !MongoShapeGuard.known(name)) {
            walk.unknownStages.add(name);
        }
        List<PlanNode> children = new ArrayList<>();
        for (Document child : childrenOf(stage)) {
            children.add(node(child, stage, walk));
        }

        String index = indexOf(stage, name);
        String collection = collectionOf(stage, walk);
        if (MongoShapeGuard.SCAN_STAGES.contains(name)) {
            walk.tables.add(table(collection, name, null, true,
                    number(stage.get("docsExamined")), number(stage.get("nReturned")), walk.withStats));
        } else if (MongoShapeGuard.INDEX_STAGES.contains(name)) {
            boolean fetched = parent != null && "FETCH".equals(stageOf(parent));
            Document counted = fetched ? parent : stage;
            walk.tables.add(table(collection, name, index, false,
                    number(counted.get("docsExamined")), number(counted.get("nReturned")), walk.withStats));
        } else if ("EQ_LOOKUP".equals(name) || "EQ_LOOKUP_UNWIND".equals(name)) {
            walk.lookup = true;
            String foreign = shortName(stage.get("foreignCollection"));
            walk.tables.add(table(foreign, name, index, index == null, null, null, false));
        }

        boolean sorts = "SORT".equals(name);
        walk.sorts |= sorts;
        return new PlanNode(name, index != null ? index : collection, name, index,
                number(stage.get("docsExamined")), null, sorts, spilledHere(stage), children);
    }

    private static TableAnalysis table(String collection, String stage, String index, boolean scan,
                                       Long examined, Long returned, boolean withStats) {
        TableAnalysis table = new TableAnalysis();
        table.setTableName(collection);
        table.setAccessType(stage);
        table.setKeyUsed(index);
        table.setFullTableScan(scan);
        table.setUsesIndex(index != null);
        if (withStats) {
            table.setEstimatedRows(examined);
            if (examined != null && examined > 0 && returned != null) {
                table.setFiltered(100.0 * returned / examined);
            }
        }
        return table;
    }

    private void summarise(QueryAnalysisResult result, MongoQuery query, Document stats, Walk walk,
                           PlanNode tree, boolean spilled) {
        List<TableAnalysis> tables = walk.tables;
        result.setTables(tables);
        result.setPlanTree(tree);
        result.setQueryCost(null);
        result.setHasFullTableScan(tables.stream().anyMatch(TableAnalysis::isFullTableScan));
        result.setUsesIndex(tables.stream().anyMatch(TableAnalysis::isUsesIndex));
        result.setKeyUsed(tables.stream().map(TableAnalysis::getKeyUsed).filter(Objects::nonNull)
                .findFirst().orElse(null));
        result.setAccessType(result.isHasFullTableScan()
                ? "COLLSCAN"
                : tables.stream().filter(TableAnalysis::isUsesIndex).map(TableAnalysis::getAccessType)
                        .findFirst().orElse(tree.kind()));
        result.setUsesFilesort(walk.sorts);
        result.setUsesTemporaryTable(spilled);
        result.setEstimatedRows(number(stats.get("totalDocsExamined")));
        result.setExecutionMillis(number(stats.get("executionTimeMillis")));

        boolean countsOnly = query.operation() == MongoQuery.Operation.COUNT
                || query.operation() == MongoQuery.Operation.DELETE;
        result.setEstimatedRowsReturned(countsOnly
                ? tables.stream().map(TableAnalysis::getEstimatedRowsReturned).filter(Objects::nonNull)
                        .findFirst().orElse(null)
                : number(stats.get("nReturned")));
        result.setFiltered(tables.stream().filter(t -> t.getEstimatedRows() != null)
                .max(Comparator.comparing(TableAnalysis::getEstimatedRows))
                .map(TableAnalysis::getFiltered).orElse(null));

        for (String key : MongoShapeGuard.REQUIRED_STATS) {
            if (!stats.containsKey(key)) {
                result.addWarning("MongoDB's explain output did not include " + key
                        + ", so the figure it supplies is not shown.");
            }
        }
        if (!walk.unknownStages.isEmpty()) {
            result.addWarning("MongoDB's plan includes a stage QuerySense does not read ("
                    + String.join(", ", walk.unknownStages) + "). Figures that depend on it are left out "
                    + "rather than guessed.");
        }
        addWarnings(result, tables, query.collection());
    }

    private static void addWarnings(QueryAnalysisResult result, List<TableAnalysis> tables, String collection) {
        Set<String> emitted = new LinkedHashSet<>();
        for (TableAnalysis table : tables) {
            String name = table.getTableName();
            if (table.isFullTableScan() && emitted.add(name)) {
                result.addWarning("Collection scan detected on '" + name + "'. MongoDB read every document in "
                        + "this collection. If the query is slow, check whether an index on the filtered fields "
                        + "could reduce the documents examined.");
            }
        }
        if (result.isUsesTemporaryTable()) {
            result.addWarning("The grouping stage on '" + collection + "' spilled to disk. It needed more memory "
                    + "than MongoDB allows a single stage, so part of the work was written out and read back.");
        }
        if (result.isUsesFilesort()) {
            result.addWarning("In-memory sort used for '" + collection + "'. If the query is slow, check whether "
                    + "an index matching the sort fields could let MongoDB return documents already in order.");
        }
    }

    private static void markPossibleKeys(Document planner, Walk walk) {
        Set<String> rejectedIndexes = new LinkedHashSet<>();
        indexNames(planner.get("rejectedPlans"), rejectedIndexes);
        if (rejectedIndexes.isEmpty()) {
            return;
        }
        String keys = String.join(", ", rejectedIndexes);
        walk.tables.stream().filter(TableAnalysis::isFullTableScan).forEach(t -> t.setPossibleKeys(keys));
    }

    private static void indexNames(Object node, Set<String> out) {
        if (node instanceof Document document) {
            if (document.get("indexName") instanceof String name) {
                out.add(name);
            }
            document.values().forEach(v -> indexNames(v, out));
        } else if (node instanceof List<?> list) {
            list.forEach(v -> indexNames(v, out));
        }
    }

    private static List<Document> childrenOf(Document stage) {
        List<Document> children = new ArrayList<>();
        for (String key : List.of("inputStage", "outerStage", "innerStage")) {
            if (stage.get(key) instanceof Document child) {
                children.add(child);
            }
        }
        if (stage.get("inputStages") instanceof List<?> list) {
            list.stream().filter(Document.class::isInstance).map(Document.class::cast).forEach(children::add);
        }
        return children;
    }

    private static String indexOf(Document stage, String name) {
        if (stage.get("indexName") instanceof String index) {
            return index;
        }
        return name != null && name.endsWith("IDHACK") ? "_id_" : null;
    }

    private static String collectionOf(Document stage, Walk walk) {
        String name = shortName(stage.get("nss"));
        return name != null ? name : walk.collection;
    }

    private static String shortName(Object namespace) {
        if (!(namespace instanceof String nss)) {
            return null;
        }
        int dot = nss.indexOf('.');
        return dot < 0 ? nss : nss.substring(dot + 1);
    }

    private static boolean spilledHere(Document stage) {
        boolean group = "GROUP".equalsIgnoreCase(stageOf(stage)) || stage.containsKey("$group");
        return group && Boolean.TRUE.equals(stage.get("usedDisk"));
    }

    private static boolean spilledAnywhere(Object node) {
        if (node instanceof Document document) {
            return spilledHere(document) || document.values().stream().anyMatch(MongoExplainParser::spilledAnywhere);
        }
        if (node instanceof List<?> list) {
            return list.stream().anyMatch(MongoExplainParser::spilledAnywhere);
        }
        return false;
    }

    private static String stageOf(Document stage) {
        return stage != null && stage.get("stage") instanceof String name ? name : null;
    }

    private static Document documentAt(Document parent, String key) {
        return parent != null && parent.get(key) instanceof Document child ? child : null;
    }

    private static Long number(Object value) {
        return value instanceof Number n ? n.longValue() : null;
    }

    private static void fail(QueryAnalysisResult result) {
        result.addWarning(UNREADABLE);
        result.setExplainFailed(true);
    }
}
