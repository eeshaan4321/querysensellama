package com.querysense.backend.optimization;

import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.mongo.MongoShellParser;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MongoQueryRewriterTest {

    private final ActiveSchemaProvider activeSchema = new ActiveSchemaProvider();
    private final MongoShellParser parser = new MongoShellParser();
    private final MongoQueryRewriter rewriter = new MongoQueryRewriter(activeSchema, parser);

    private QueryRewrite rewrite(String mongosh) {
        return rewriter.rewrite(mongosh);
    }

    @Test
    @DisplayName("serves MongoDB")
    void servesMongo() {
        assertThat(rewriter.dialect()).isEqualTo(Dialect.MONGODB);
    }

    @Test
    @DisplayName("RW2: an $or of equalities on one field becomes $in, and the other conditions stay")
    void orBecomesIn() {
        QueryRewrite result = rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}], "
                + "\"name\": \"Asha\"}, {\"name\": 1})");

        assertThat(result.changed()).isTrue();
        assertThat(result.changes()).extracting(RewriteChange::ruleId).containsExactly("RW2");
        assertThat(parser.parse(result.sql()).filter()).isEqualTo(parser.parse(
                "db.customers.find({\"city\": {\"$in\": [\"Pune\", \"Delhi\"]}, \"name\": \"Asha\"})").filter());
        assertThat(result.changes().get(0).explanation()).contains("'city'").contains("identical");
    }

    @Test
    @DisplayName("RW2 accepts {$eq: v} branches and rewrites every $match in a pipeline")
    void eqBranchesAndPipelines() {
        QueryRewrite result = rewrite("db.orders.aggregate([{\"$match\": {\"$or\": [{\"status\": {\"$eq\": \"NEW\"}}, "
                + "{\"status\": \"PAID\"}]}}, {\"$group\": {\"_id\": \"$status\"}}])");

        assertThat(result.sql()).contains("\"$in\"").doesNotContain("\"$or\"");
        assertThat(parser.parse(result.sql()).pipeline()).hasSize(2);
    }

    @Test
    @DisplayName("RW2 declines an $or across two fields")
    void orAcrossFieldsDeclines() {
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"name\": \"Asha\"}]}, {\"name\": 1})")
                .changed()).isFalse();
    }

    @Test
    @DisplayName("RW2 declines operators, regexes, embedded documents and a single branch")
    void orWithOperatorsDeclines() {
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": {\"$ne\": \"Pune\"}}, {\"city\": \"Delhi\"}]}, {\"name\": 1})")
                .changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": {\"$regex\": \"pu\"}}, {\"city\": \"Delhi\"}]}, {\"name\": 1})")
                .changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": {\"a\": 1}}, {\"city\": \"Delhi\"}]}, {\"name\": 1})")
                .changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Delhi\"}]}, {\"name\": 1})").changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}], "
                + "\"city\": \"Agra\"}, {\"name\": 1})").changed()).isFalse();
    }

    @Test
    @DisplayName("unparseable text, an unknown collection and nothing to do all come back unchanged")
    void unchangedCases() {
        assertThat(rewrite("db.customers.find({city: )").sql()).isEqualTo("db.customers.find({city: )");
        assertThat(rewrite("db.clients.find({\"$or\": [{\"a\": 1}, {\"a\": 2}]})").changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"city\": \"Pune\"}, {\"name\": 1})").changed()).isFalse();
        assertThat(rewriter.rewrite(null).changed()).isFalse();
    }

    @Test
    @DisplayName("a trailing semicolon survives the rewrite")
    void semicolonKept() {
        assertThat(rewrite("db.customers.find({\"$or\": [{\"city\": \"Pune\"}, {\"city\": \"Delhi\"}]}, {\"name\": 1});").sql())
                .endsWith(";");
    }

    @Test
    @DisplayName("the MySQL rewriter is a Rewriter for MySQL and behaves as before")
    void mysqlRewriter() {
        QueryRewriter mysql = new QueryRewriter(activeSchema);

        assertThat(mysql.dialect()).isEqualTo(Dialect.MYSQL);
        String sql = "SELECT * FROM customers WHERE city = 'a' OR city = 'b';";
        assertThat(mysql.rewrite(sql)).isEqualTo(mysql.rewrite(sql, "MySQL"));
    }
}
