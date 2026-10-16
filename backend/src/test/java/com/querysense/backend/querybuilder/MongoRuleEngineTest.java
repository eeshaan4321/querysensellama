package com.querysense.backend.querybuilder;

import com.querysense.backend.analysis.SchemaMetadata;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MongoRuleEngineTest {

    private RuleBasedQueryBuilder builder;

    @BeforeEach
    void setUp() {
        ActiveSchemaProvider schemas = new ActiveSchemaProvider();
        SchemaMetadata mysql = metadata(Dialect.MYSQL);
        SchemaMetadata mongo = metadata(Dialect.MONGODB);
        builder = new RuleBasedQueryBuilder(TestParsers.parser(schemas, mysql, mongo));
    }

    private static SchemaMetadata metadata(Dialect dialect) {
        SchemaMetadata m = mock(SchemaMetadata.class);
        when(m.dialect()).thenReturn(dialect);
        when(m.getColumnNames(anyString())).thenReturn(List.of());
        when(m.getDistinctValues(anyString(), anyString())).thenReturn(List.of());
        when(m.getDistinctValues(eq("customers"), eq("city"))).thenReturn(List.of("Mumbai", "Pune", "Delhi"));
        return m;
    }

    @Test
    @DisplayName("a simple MongoDB question is now built by the rule engine")
    void mongoIsBuilt() {
        RuleBuildResult result = builder.build("Show all customers", "MongoDB");
        assertThat(result.built()).isTrue();
        assertThat(result.sql()).isEqualTo("db.customers.find({})");
    }

    @Test
    @DisplayName("the filter carries the stored value for MongoDB and the typed value for SQL")
    void storedValueForMongoTypedForSql() {
        assertThat(builder.build("customers in mumbai", "MongoDB").sql())
                .isEqualTo("db.customers.find({\"city\": \"Mumbai\"})");
        assertThat(builder.build("customers in mumbai", "MySQL").sql())
                .isEqualTo("SELECT * FROM customers WHERE city = 'mumbai';");
    }

    @Test
    @DisplayName("the join sample prompt builds a $lookup on the declared reference")
    void joinBuildsLookup() {
        RuleBuildResult result = builder.build("Which customers placed more than three orders", "MongoDB");
        assertThat(result.built()).isTrue();
        assertThat(result.sql())
                .startsWith("db.customers.aggregate([")
                .contains("\"$lookup\": {\"from\": \"orders\", \"localField\": \"_id\", \"foreignField\": \"customer_id\"")
                .contains("{\"$gt\": 3}");
        assertThat(result.trace().joinEdge().fromColumn()).isEqualTo("customer_id");
    }

    @Test
    @DisplayName("counting and deleting use countDocuments and deleteMany")
    void countAndDelete() {
        assertThat(builder.build("how many orders are there", "MongoDB").sql())
                .isEqualTo("db.orders.countDocuments({})");
        assertThat(builder.build("delete customers in pune", "MongoDB").sql())
                .isEqualTo("db.customers.deleteMany({\"city\": \"Pune\"})");
        assertThat(builder.build("delete all customers", "MongoDB").built()).isFalse();
    }

    @Test
    @DisplayName("an emitter refusal becomes a decline with the emitter's reason")
    void emitterRefusalDeclines() {
        RuleBuildResult result = builder.build("how many customers per city sorted by name", "MongoDB");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("name");
    }

    @Test
    @DisplayName("a with-value MongoDB can't confirm the stored form of declines instead of guessing its case")
    void unconfirmedWithValueDeclinesOnMongo() {
        RuleBuildResult result = builder.build("orders with status shipped", "MongoDB");
        assertThat(result.built()).isFalse();
        assertThat(result.trace().declineReason()).contains("shipped");
    }

    @Test
    @DisplayName("the same unconfirmed with-value still builds on MySQL with the typed value")
    void unconfirmedWithValueBuildsOnMySql() {
        assertThat(builder.build("orders with status shipped", "MySQL").sql())
                .isEqualTo("SELECT * FROM orders WHERE status = 'shipped';");
    }

    @Test
    @DisplayName("a numeric with-value needs no stored form on MongoDB")
    void numericWithValueBuildsOnMongo() {
        assertThat(builder.build("orders with quantity 3", "MongoDB").built()).isTrue();
    }

    @Test
    @DisplayName("a breakdown with nothing to total or count declines on MongoDB")
    void groupingWithoutAggregateDeclinesOnMongo() {
        for (String question : List.of("amount of payments per method",
                "quantity of orders per status")) {
            RuleBuildResult result = builder.build(question, "MongoDB");
            assertThat(result.built()).as("%s -> %s", question, result.sql()).isFalse();
            assertThat(result.trace().declineReason()).as(question).contains("nothing to group");
        }
    }

    @Test
    @DisplayName("a counted breakdown still builds on MongoDB")
    void countedBreakdownBuildsOnMongo() {
        RuleBuildResult result = builder.build("how many customers are there per city", "MongoDB");
        assertThat(result.built()).isTrue();
        assertThat(result.sql()).contains("\"$group\"");
    }

    @Test
    @DisplayName("an unknown database type is refused")
    void unknownDatabaseType() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> builder.build("Show all customers", "Oracle"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
