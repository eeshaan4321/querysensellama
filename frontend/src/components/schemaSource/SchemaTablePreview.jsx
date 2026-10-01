import './SchemaTablePreview.css';

const SQL_NOTE = 'These tables were created from your schema and filled with generated rows, so that EXPLAIN has real statistics to plan against. The access paths and index choices it reports are real; the data itself is synthetic.';
const MONGO_NOTE = 'These collections were created from your schema, with its validators and indexes, and filled with generated documents, so that explain has real data to plan against. The plan stages and index choices it reports are real; the data itself is synthetic.';

function indexText(index) {
  const keys = index.keys.map((key) => `${key.column}: ${key.direction}`).join(', ');
  return `index { ${keys} }${index.unique ? ' · unique' : ''}`;
}

export function SchemaTablePreview({ schema }) {
  if (!schema || !schema.tables.length) return null;

  const isMongo = schema.dialect === 'MONGODB';
  const [one, many] = isMongo ? ['collection', 'collections'] : ['table', 'tables'];
  const rowUnit = isMongo ? 'documents' : 'rows';
  const foreignKeysFrom = (table) => schema.foreignKeys.filter((fk) => fk.fromTable === table);
  const indexesOn = (table) => (schema.indexes || []).filter((index) => index.table === table);

  return (
    <section className="schema-preview">
      <header className="schema-preview__head">
        <h2 className="schema-preview__title">{schema.label}</h2>
        <span className="schema-preview__meta">
          {schema.tables.length} {schema.tables.length === 1 ? one : many}
          {schema.catalog ? ` · ${schema.catalog}` : ' · connected database'}
        </span>
      </header>

      {!schema.builtIn && <p className="schema-preview__note">{isMongo ? MONGO_NOTE : SQL_NOTE}</p>}

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
                <span className="schema-preview__rows">{table.rowCount.toLocaleString()} {rowUnit}</span>
              )}
            </div>

            <div className="schema-preview__columns">
              {table.columns.map((column) => (
                <span key={column} className="schema-preview__column">
                  {isMongo ? (
                    <>
                      <span className="schema-preview__path">{column}</span>
                      {table.fieldTypes?.[column] && (
                        <span className="schema-preview__type">{table.fieldTypes[column]}</span>
                      )}
                    </>
                  ) : (
                    column
                  )}
                </span>
              ))}
            </div>

            {isMongo && table.embeddedArrays?.length > 0 && (
              <div className="schema-preview__arrays">
                {`Embedded array${table.embeddedArrays.length === 1 ? '' : 's'}: ${table.embeddedArrays.join(', ')}`}
              </div>
            )}

            {foreignKeysFrom(table.name).map((fk) => (
              <div key={`${fk.fromColumn}-${fk.toTable}-${fk.toColumn}`} className="schema-preview__fk">
                {fk.fromColumn} → {fk.toTable}.{fk.toColumn}
              </div>
            ))}

            {isMongo && indexesOn(table.name).map((index) => (
              <div key={indexText(index)} className="schema-preview__index">
                {indexText(index)}
              </div>
            ))}
          </li>
        ))}
      </ul>
    </section>
  );
}
