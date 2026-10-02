package com.querysense.backend.schema.upload;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.ReplaceOptions;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
import com.querysense.backend.schema.KnownMongoSchema;
import com.querysense.backend.schema.KnownSchema;
import org.bson.Document;
import org.bson.conversions.Bson;
import com.querysense.backend.schema.json.JsonSchemaUploadParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MongoDemoSeederTest {

    private MongoClient client;
    private ListCollectionNamesIterable names;
    private MongoCollection<Document> marker;
    private MongoCollection<Document> demoCollection;
    private MongoProvisioner provisioner;
    private MongoSeeder seeder;
    private ActiveSchemaProvider activeSchema;
    private MongoDemoSeeder demoSeeder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        names = mock(ListCollectionNamesIterable.class);
        when(client.getDatabase("querysense")).thenReturn(database);
        when(database.listCollectionNames()).thenReturn(names);
        marker = mock(MongoCollection.class);
        demoCollection = mock(MongoCollection.class);
        when(database.getCollection(any())).thenReturn(demoCollection);
        when(database.getCollection(MongoDemoSeeder.SEED_MARKER)).thenReturn(marker);
        provisioner = mock(MongoProvisioner.class);
        seeder = mock(MongoSeeder.class);
        when(seeder.seed(any(), eq("querysense"))).thenReturn(new SeedReport(Map.of("orders", 5), List.of()));
        activeSchema = new ActiveSchemaProvider();
        demoSeeder = new MongoDemoSeeder(client, new JsonSchemaUploadParser(), new SchemaUploadValidator(40),
                provisioner, seeder, activeSchema, true, 200);
    }

    @SuppressWarnings("unchecked")
    private void existing(String... collections) {
        when(names.into(any())).thenAnswer(inv -> {
            Collection<String> target = inv.getArgument(0);
            target.addAll(List.of(collections));
            return target;
        });
    }

    @Test
    @DisplayName("an empty querysense database gets the demo provisioned and seeded, and the Mongo slot refreshed")
    void seedsWhenEmpty() {
        existing("unrelated");
        AtomicInteger changes = new AtomicInteger();
        activeSchema.onSchemaChanged(d -> {
            if (d == Dialect.MONGODB) {
                changes.incrementAndGet();
            }
        });

        assertThat(demoSeeder.seedIfIncomplete()).isTrue();

        verify(provisioner).provision(any(), eq("querysense"));
        verify(seeder).seed(any(), eq("querysense"));
        verify(marker).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
        assertThat(changes).hasValue(1);
        assertThat(activeSchema.isBuiltIn(Dialect.MONGODB)).isTrue();
    }

    @Test
    @DisplayName("a demo marked as seeded is left exactly as it is")
    void leavesSeededDemoAlone() {
        when(marker.countDocuments(any(Bson.class))).thenReturn(1L);
        existing("orders");

        assertThat(demoSeeder.seedIfIncomplete()).isFalse();

        verify(provisioner, never()).provision(any(), any());
        verify(seeder, never()).seed(any(), any());
    }

    @Test
    @DisplayName("an unreachable MongoDB is logged and skipped, never thrown")
    void unreachableIsSkipped() {
        when(names.into(any())).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        assertThat(demoSeeder.seedIfIncomplete()).isFalse();

        verify(provisioner, never()).provision(any(), any());
    }

    @Test
    @DisplayName("a demo seeded before the marker existed counts as complete when every collection is full, and gets marked")
    void fullUnmarkedDemoIsMarkedNotReseeded() {
        existing(KnownMongoSchema.TABLES.stream().map(KnownSchema.Table::name).toArray(String[]::new));
        when(demoCollection.estimatedDocumentCount()).thenReturn(200L);

        assertThat(demoSeeder.seedIfIncomplete()).isFalse();

        verify(provisioner, never()).provision(any(), any());
        verify(marker).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test
    @DisplayName("a demo whose first seeding stopped partway is provisioned and seeded again, then marked")
    void partialDemoIsReseeded() {
        existing("orders", "customers");
        when(demoCollection.estimatedDocumentCount()).thenReturn(200L);

        assertThat(demoSeeder.seedIfIncomplete()).isTrue();

        verify(provisioner).provision(any(), eq("querysense"));
        verify(seeder).seed(any(), eq("querysense"));
        verify(marker).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
    }

    @Test
    @DisplayName("every collection present but one short of documents is still incomplete")
    void shortCollectionIsIncomplete() {
        existing(KnownMongoSchema.TABLES.stream().map(KnownSchema.Table::name).toArray(String[]::new));
        when(demoCollection.estimatedDocumentCount()).thenReturn(200L, 200L, 37L);

        assertThat(demoSeeder.seedIfIncomplete()).isTrue();

        verify(provisioner).provision(any(), eq("querysense"));
    }

    @Test
    @DisplayName("a failed seeding writes no marker, so the next startup tries again")
    void failedSeedingLeavesNoMarker() {
        existing("unrelated");
        when(seeder.seed(any(), eq("querysense"))).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        assertThat(demoSeeder.seedIfIncomplete()).isFalse();

        verify(marker, never()).replaceOne(any(Bson.class), any(Document.class), any(ReplaceOptions.class));
    }
}
