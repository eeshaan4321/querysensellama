package com.querysense.backend.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/** Holds the schema currently in use, built-in or uploaded. */
@Component
public class ActiveSchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveSchemaProvider.class);

    private volatile SchemaSnapshot active = KnownSchema.builtIn();
    private final List<Runnable> schemaChangedListeners = new CopyOnWriteArrayList<>();

    public SchemaSnapshot active() {
        return active;
    }

    public boolean isBuiltIn() {
        return active.builtIn();
    }

    public String catalog() {
        return active.catalog();
    }

    public List<KnownSchema.Table> tables() {
        return active.tables();
    }

    public List<KnownSchema.ForeignKey> foreignKeys() {
        return active.foreignKeys();
    }

    public List<String> tableNames() {
        return active.tableNames();
    }

    public Optional<KnownSchema.Table> findTable(String name) {
        return SchemaLookup.findTable(active.tables(), name);
    }

    public List<KnownSchema.Table> detectTablesMentioned(String text) {
        return SchemaLookup.detectTablesMentioned(active.tables(), text);
    }

    public void onSchemaChanged(Runnable listener) {
        schemaChangedListeners.add(listener);
    }

    public SchemaSnapshot publish(SchemaSnapshot snapshot) {
        this.active = snapshot;
        log.info("Active schema is now '{}' — {} tables, catalog {}",
                snapshot.label(), snapshot.tables().size(),
                snapshot.catalog() == null ? "(connected database)" : snapshot.catalog());
        schemaChangedListeners.forEach(Runnable::run);
        return snapshot;
    }

    public SchemaSnapshot resetToBuiltIn() {
        return publish(KnownSchema.builtIn());
    }
}
