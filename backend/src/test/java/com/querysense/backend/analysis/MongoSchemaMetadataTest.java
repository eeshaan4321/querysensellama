package com.querysense.backend.analysis;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.MongoClient;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import org.bson.BsonBoolean;
import org.bson.BsonDouble;
import org.bson.BsonInt32;
import org.bson.BsonInt64;
import org.bson.BsonString;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
}
