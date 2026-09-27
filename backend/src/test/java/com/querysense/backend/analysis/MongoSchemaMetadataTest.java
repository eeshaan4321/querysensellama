package com.querysense.backend.analysis;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.DistinctIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonBoolean;
import org.bson.BsonDateTime;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonObjectId;
import org.bson.BsonString;
import org.bson.BsonValue;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoSchemaMetadataTest {

    @Test
    @DisplayName("serves MongoDB, and has no live column list to check drift against")
    void dialectAndColumns() {
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(mock(MongoClient.class), new ActiveSchemaProvider());
        assertThat(metadata.dialect()).isEqualTo(Dialect.MONGODB);
        assertThat(metadata.getColumnNames("customers")).isEmpty();
    }

    @Test
    @DisplayName("a field the active Mongo schema does not declare is never queried")
    void undeclaredFieldIsNotQueried() {
        MongoClient client = mock(MongoClient.class);
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider());

        assertThat(metadata.getDistinctValues("customers", "age")).isEmpty();
        verify(client, never()).getDatabase(anyString());
    }

    @Test
    @DisplayName("a server that is down gives no values rather than an error")
    void serverDownGivesNoValues() {
        MongoClient client = mock(MongoClient.class);
        when(client.getDatabase(anyString())).thenThrow(new MongoTimeoutException("no server"));
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider());

        assertThat(metadata.getDistinctValues("customers", "city")).isEmpty();
    }

    @Test
    @DisplayName("stored values become the text the rule engine compares against")
    void valuesAsText() {
        assertThat(MongoSchemaMetadata.asText(new BsonString("Mumbai"))).isEqualTo("Mumbai");
        assertThat(MongoSchemaMetadata.asText(new BsonInt32(3))).isEqualTo("3");
        assertThat(MongoSchemaMetadata.asText(new BsonInt64(4L))).isEqualTo("4");
        assertThat(MongoSchemaMetadata.asText(new BsonDouble(2.5))).isEqualTo("2.5");
        assertThat(MongoSchemaMetadata.asText(new BsonBoolean(true))).isEqualTo("true");
    }

    @Test
    @DisplayName("an ObjectId becomes its hex string, not BsonObjectId{value=...}")
    void objectIdAsText() {
        assertThat(MongoSchemaMetadata.asText(new BsonObjectId(new ObjectId("64b7f0c2a1b2c3d4e5f60718"))))
                .isEqualTo("64b7f0c2a1b2c3d4e5f60718");
    }

    @Test
    @DisplayName("a date becomes an ISO instant, not BsonDateTime{value=...}")
    void dateTimeAsText() {
        assertThat(MongoSchemaMetadata.asText(new BsonDateTime(0L))).isEqualTo("1970-01-01T00:00:00Z");
    }

    @Test
    @DisplayName("a failure is not retried again within the unavailable window, even for a different field")
    void failureSuppressesRetriesWithinWindow() {
        MongoClient client = mock(MongoClient.class);
        when(client.getDatabase(anyString())).thenThrow(new MongoTimeoutException("no server"));
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider());

        assertThat(metadata.getDistinctValues("customers", "city")).isEmpty();
        assertThat(metadata.getDistinctValues("customers", "phone")).isEmpty();

        verify(client, times(1)).getDatabase(anyString());
    }

    @Test
    @DisplayName("after the unavailable window passes, the client is tried again")
    void retriesAfterWindowPasses() {
        AtomicLong now = new AtomicLong(0L);
        MongoClient client = mock(MongoClient.class);
        when(client.getDatabase(anyString())).thenThrow(new MongoTimeoutException("no server"));
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider(), now::get);

        assertThat(metadata.getDistinctValues("customers", "city")).isEmpty();
        now.set(30_001L);
        assertThat(metadata.getDistinctValues("customers", "city")).isEmpty();

        verify(client, times(2)).getDatabase(anyString());
    }

    @Test
    @DisplayName("a failure is not cached; once the window passes and the client succeeds, values are returned")
    void failureNotCachedRetriesSucceedAfterWindow() {
        AtomicLong now = new AtomicLong(0L);
        AtomicInteger calls = new AtomicInteger(0);
        MongoSchemaMetadata metadata = new MongoSchemaMetadata(
                mock(MongoClient.class), new ActiveSchemaProvider(), now::get) {
            @Override
            List<String> readDistinct(String tableName, String columnName) {
                if (calls.getAndIncrement() == 0) {
                    throw new MongoTimeoutException("no server");
                }
                return List.of("Mumbai");
            }
        };

        assertThat(metadata.getDistinctValues("customers", "city")).isEmpty();
        now.set(30_001L);
        assertThat(metadata.getDistinctValues("customers", "city")).containsExactly("Mumbai");
    }

    @Test
    @DisplayName("distinct stops reading after MAX_VALUES, not the whole cursor")
    void stopsAtMaxValues() {
        MongoClient client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        MongoCollection<Document> collection = mock(MongoCollection.class);
        DistinctIterable<BsonValue> iterable = mock(DistinctIterable.class);
        MongoCursor<BsonValue> cursor = mock(MongoCursor.class);

        when(client.getDatabase(anyString())).thenReturn(database);
        when(database.getCollection("customers")).thenReturn(collection);
        when(collection.distinct("city", BsonValue.class)).thenReturn(iterable);
        when(iterable.iterator()).thenReturn(cursor);

        AtomicInteger produced = new AtomicInteger(0);
        when(cursor.hasNext()).thenReturn(true);
        when(cursor.next()).thenAnswer(inv -> new BsonString("v" + produced.getAndIncrement()));

        MongoSchemaMetadata metadata = new MongoSchemaMetadata(client, new ActiveSchemaProvider());
        List<String> values = metadata.getDistinctValues("customers", "city");

        assertThat(values).hasSize(500);
        verify(cursor, times(500)).next();
        verify(cursor).close();
    }
}
