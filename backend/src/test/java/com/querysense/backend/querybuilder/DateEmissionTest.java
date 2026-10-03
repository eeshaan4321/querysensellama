package com.querysense.backend.querybuilder;

import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownSchema;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DateEmissionTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private static QueryIntent lastMonth() {
        QueryIntent.ColumnRef date = new QueryIntent.ColumnRef(null, "order_date");
        return new QueryIntent(QueryIntent.Verb.SELECT, "orders", null,
                List.of(new QueryIntent.SelectItem(QueryIntent.Star.INSTANCE)),
                List.of(new QueryIntent.Predicate(date, QueryIntent.Op.GTE, List.of(QueryIntent.Literal.date(LocalDate.of(2026, 9, 1), IST))),
                        new QueryIntent.Predicate(date, QueryIntent.Op.LT, List.of(QueryIntent.Literal.date(LocalDate.of(2026, 10, 1), IST)))),
                List.of(), List.of(), null);
    }

    @Test
    @DisplayName("SQL writes ISO dates")
    void sql() {
        assertThat(new SqlEmitter().emit(lastMonth()))
                .isEqualTo("SELECT * FROM orders WHERE order_date >= '2026-09-01' AND order_date < '2026-10-01';");
    }

    @Test
    @DisplayName("mergedRange: MongoDB writes ISODate at local midnight, both bounds in one condition")
    void mergedRange() {
        assertThat(new MongoEmitter(new ActiveSchemaProvider()).emit(lastMonth())).isEqualTo(
                "db.orders.find({\"order_date\": {\"$gte\": ISODate(\"2026-08-31T18:30:00Z\"), "
                        + "\"$lt\": ISODate(\"2026-09-30T18:30:00Z\")}})");
    }

    @Test
    @DisplayName("a pinned clock reads today in its zone; the demo knows its date columns")
    void clockAndTypes() {
        RulesClock clock = RulesClock.fixed(LocalDate.of(2026, 10, 2), IST);
        assertThat(clock.today()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(new RulesClock("Asia/Kolkata", "2026-10-02").today()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(KnownSchema.FIELD_TYPES.get("orders").get("order_date")).isEqualTo("DATE");
        assertThat(SchemaBinder.isDateType("datetime")).isTrue();
        assertThat(SchemaBinder.isDateType("date")).isTrue();
        assertThat(SchemaBinder.isDateType("VARCHAR")).isFalse();
    }
}
