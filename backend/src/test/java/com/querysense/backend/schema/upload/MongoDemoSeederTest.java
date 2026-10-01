package com.querysense.backend.schema.upload;

import com.mongodb.MongoTimeoutException;
import com.mongodb.client.ListCollectionNamesIterable;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoDatabase;
import com.querysense.backend.dialect.Dialect;
import com.querysense.backend.schema.ActiveSchemaProvider;
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
    private MongoProvisioner provisioner;
    private MongoSeeder seeder;
    private ActiveSchemaProvider activeSchema;
    private MongoDemoSeeder demoSeeder;

    @BeforeEach
    void setUp() {
        client = mock(MongoClient.class);
        MongoDatabase database = mock(MongoDatabase.class);
        names = mock(ListCollectionNamesIterable.class);
        when(client.getDatabase("querysense")).thenReturn(database);
        when(database.listCollectionNames()).thenReturn(names);
        provisioner = mock(MongoProvisioner.class);
        seeder = mock(MongoSeeder.class);
        when(seeder.seed(any(), eq("querysense"))).thenReturn(new SeedReport(Map.of("orders", 5), List.of()));
        activeSchema = new ActiveSchemaProvider();
        demoSeeder = new MongoDemoSeeder(client, new JsonSchemaUploadParser(), new SchemaUploadValidator(40),
                provisioner, seeder, activeSchema, true);
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

        assertThat(demoSeeder.seedIfEmpty()).isTrue();

        verify(provisioner).provision(any(), eq("querysense"));
        verify(seeder).seed(any(), eq("querysense"));
        assertThat(changes).hasValue(1);
        assertThat(activeSchema.isBuiltIn(Dialect.MONGODB)).isTrue();
    }

    @Test
    @DisplayName("any demo collection already present means nothing is touched")
    void leavesExistingDemoAlone() {
        existing("orders");

        assertThat(demoSeeder.seedIfEmpty()).isFalse();

        verify(provisioner, never()).provision(any(), any());
        verify(seeder, never()).seed(any(), any());
    }

    @Test
    @DisplayName("an unreachable MongoDB is logged and skipped, never thrown")
    void unreachableIsSkipped() {
        when(names.into(any())).thenThrow(new MongoTimeoutException("Timed out after 2000 ms"));

        assertThat(demoSeeder.seedIfEmpty()).isFalse();

        verify(provisioner, never()).provision(any(), any());
    }
}
