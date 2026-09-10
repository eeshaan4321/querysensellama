package com.querysense.backend.schema;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ActiveSchemaProvider
 *
 * WHY THIS CLASS EXISTS:
 * KnownSchema was static, so every consumer of it was pinned to the
 * built-in nine-table demo schema at compile time. The schema-upload
 * feature needs the same consumers to read an uploaded schema instead.
 * This holds the current SchemaSnapshot and every consumer now injects it.
 *
 * WHY volatile RATHER THAN A LOCK:
 * A snapshot is immutable and swapping is a single reference assignment.
 * volatile gives the visibility guarantee across request threads without
 * putting a lock on a field that is read on the hot path of every
 * generated query.
 *
 * WHY LISTENERS RATHER THAN CALLING SchemaMetadataService DIRECTLY:
 * SchemaMetadataService caches live column and value lookups, and those
 * caches MUST be flushed when the schema changes — otherwise the first
 * upload keeps serving the demo schema's columns to SqlPreflightValidator
 * and the demo's city values to the rule engine, silently, while looking
 * correct. But SchemaMetadataService already depends on this class for
 * findTable, so calling it from here would be a dependency cycle. It
 * registers a flush callback via onSchemaChanged instead, and the arrow
 * points one way.
 */
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

    /** Null means "use the connection's existing catalog" — see SchemaSnapshot. */
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
