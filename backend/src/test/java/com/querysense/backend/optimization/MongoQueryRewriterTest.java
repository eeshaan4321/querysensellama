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

    @Test
    @DisplayName("RW1: a find without a projection names the declared top-level fields, _id left to the default")
    void projectionNamesFields() {
        QueryRewrite result = rewrite("db.orders.find({\"status\": \"PAID\"})");

        assertThat(result.changes()).extracting(RewriteChange::ruleId).containsExactly("RW1");
        assertThat(parser.parse(result.sql()).projection()).isEqualTo(new org.bson.Document("customer_id", 1)
                .append("employee_id", 1).append("order_date", 1).append("status", 1).append("items", 1));
        assertThat(result.changes().get(0).explanation()).contains("5 fields").contains("'orders'");
    }

    @Test
    @DisplayName("RW1 declines when a projection exists, when the query is not a find, or when it names an undeclared field")
    void projectionDeclines() {
        assertThat(rewrite("db.orders.find({\"status\": \"PAID\"}, {\"status\": 1})").changed()).isFalse();
        assertThat(rewrite("db.orders.countDocuments({\"status\": \"PAID\"})").changed()).isFalse();
        assertThat(rewrite("db.orders.find({\"coupon\": \"X\"})").changed()).isFalse();
        assertThat(rewrite("db.orders.find({}).sort({\"coupon\": 1})").changed()).isFalse();
    }

    @Test
    @DisplayName("RW3: an exact match on a unique field caps the result at one document, so the sort goes")
    void sortDroppedOnUniqueEquality() {
        QueryRewrite byEmail = rewrite("db.customers.find({\"email\": \"a@b.c\"}, {\"name\": 1}).sort({\"name\": 1})");
        QueryRewrite byId = rewrite("db.customers.find({\"_id\": {\"$eq\": 7}}, {\"name\": 1}).sort({\"name\": -1}).limit(3)");

        assertThat(byEmail.changes()).extracting(RewriteChange::ruleId).containsExactly("RW3");
        assertThat(parser.parse(byEmail.sql()).sort()).isNull();
        assertThat(byEmail.changes().get(0).explanation()).contains("'email'").contains("unique");
        assertThat(parser.parse(byId.sql()).sort()).isNull();
        assertThat(parser.parse(byId.sql()).limit()).isEqualTo(3);
    }

    @Test
    @DisplayName("RW3 keeps the sort for a non-unique field, a range on a unique field, or a unique index elsewhere")
    void sortKeptWithoutUniqueEquality() {
        assertThat(rewrite("db.customers.find({\"city\": \"Pune\"}, {\"name\": 1}).sort({\"name\": 1})").changed()).isFalse();
        assertThat(rewrite("db.customers.find({\"email\": {\"$gte\": \"a\"}}, {\"name\": 1}).sort({\"name\": 1})").changed()).isFalse();
        assertThat(rewrite("db.orders.find({\"email\": \"a@b.c\"}, {\"status\": 1}).sort({\"status\": 1})").changed()).isFalse();
    }

    @Test
    @DisplayName("RW1 and RW3 fire together, reported in rule order")
    void rulesInOrder() {
        assertThat(rewrite("db.customers.find({\"email\": \"a@b.c\"}).sort({\"name\": 1})").changes())
                .extracting(RewriteChange::ruleId).containsExactly("RW1", "RW3");
    }
}
