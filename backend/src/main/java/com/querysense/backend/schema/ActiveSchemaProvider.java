package com.querysense.backend.schema;

import com.querysense.backend.dialect.Dialect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** Holds the schema currently in use for each dialect, built-in or uploaded. */
@Component
public class ActiveSchemaProvider {

    private static final Logger log = LoggerFactory.getLogger(ActiveSchemaProvider.class);

    private final Map<Dialect, SchemaSnapshot> active = new EnumMap<>(Dialect.class);
    private final List<Consumer<Dialect>> schemaChangedListeners = new CopyOnWriteArrayList<>();

    public ActiveSchemaProvider() {
        active.put(Dialect.MYSQL, KnownSchema.builtIn());
        active.put(Dialect.MONGODB, KnownMongoSchema.builtIn());
    }

    public synchronized SchemaSnapshot active(Dialect dialect) {
        return active.get(dialect);
    }

    public boolean isBuiltIn(Dialect dialect) {
        return active(dialect).builtIn();
    }

    public String catalog(Dialect dialect) {
        return active(dialect).catalog();
    }

    public List<KnownSchema.Table> tables(Dialect dialect) {
        return active(dialect).tables();
    }

    public List<KnownSchema.ForeignKey> foreignKeys(Dialect dialect) {
        return active(dialect).foreignKeys();
    }

    public List<String> tableNames(Dialect dialect) {
        return active(dialect).tableNames();
    }

    public Optional<KnownSchema.Table> findTable(Dialect dialect, String name) {
        return SchemaLookup.findTable(tables(dialect), name);
    }

    public List<KnownSchema.Table> detectTablesMentioned(Dialect dialect, String text) {
        return SchemaLookup.detectTablesMentioned(tables(dialect), text);
    }

    public void onSchemaChanged(Consumer<Dialect> listener) {
        schemaChangedListeners.add(listener);
    }

    public SchemaSnapshot publish(SchemaSnapshot snapshot) {
        synchronized (this) {
            active.put(snapshot.dialect(), snapshot);
        }
        log.info("Active {} schema is now '{}' — {} tables, catalog {}",
                snapshot.dialect().label(), snapshot.label(), snapshot.tables().size(),
                snapshot.catalog() == null ? "(connected database)" : snapshot.catalog());
        schemaChangedListeners.forEach(l -> l.accept(snapshot.dialect()));
        return snapshot;
    }

    public SchemaSnapshot resetToBuiltIn(Dialect dialect) {
        return publish(dialect == Dialect.MONGODB ? KnownMongoSchema.builtIn() : KnownSchema.builtIn());
    }
}
