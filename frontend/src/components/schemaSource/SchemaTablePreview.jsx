import './SchemaTablePreview.css';

export function SchemaTablePreview({ schema }) {
  if (!schema || !schema.tables.length) return null;

  const foreignKeysFrom = (table) => schema.foreignKeys.filter((fk) => fk.fromTable === table);

  return (
    <section className="schema-preview">
      <header className="schema-preview__head">
        <h2 className="schema-preview__title">{schema.label}</h2>
        <span className="schema-preview__meta">
          {schema.tables.length} {schema.tables.length === 1 ? 'table' : 'tables'}
          {schema.catalog ? ` · ${schema.catalog}` : ' · connected database'}
        </span>
      </header>

      {!schema.builtIn && (
        <p className="schema-preview__note">
          These tables were created from your schema and filled with generated rows, so that EXPLAIN
          has real statistics to plan against. The access paths and index choices it reports are
          real; the data itself is synthetic.
        </p>
      )}

      {schema.warnings.length > 0 && (
        <ul className="schema-preview__warnings">
          {schema.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}

      <ul className="schema-preview__list">
        {schema.tables.map((table) => (
          <li key={table.name} className="schema-preview__table">
            <div className="schema-preview__table-head">
              <code className="schema-preview__table-name">{table.name}</code>
              {table.rowCount > 0 && (
                <span className="schema-preview__rows">{table.rowCount.toLocaleString()} rows</span>
              )}
            </div>

            <div className="schema-preview__columns">
              {table.columns.map((column) => (
                <span key={column} className="schema-preview__column">
                  {column}
                </span>
              ))}
            </div>

            {foreignKeysFrom(table.name).map((fk) => (
              <div key={`${fk.fromColumn}-${fk.toTable}-${fk.toColumn}`} className="schema-preview__fk">
                {fk.fromColumn} → {fk.toTable}.{fk.toColumn}
              </div>
            ))}
          </li>
        ))}
      </ul>
    </section>
  );
}
