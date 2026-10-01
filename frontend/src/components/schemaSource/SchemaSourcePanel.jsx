import { useRef, useState } from 'react';
import { Button } from '../common/Button';
import { uploadSchemaFile, uploadSchemaText } from '../../api';
import { TargetDb } from '../../api/contracts';
import { useActiveSchema } from '../../context/activeSchema.js';
import './SchemaSourcePanel.css';

const SQL_PLACEHOLDER = `CREATE TABLE clients (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80),
  city VARCHAR(60)
);`;

const MONGO_PLACEHOLDER = `{"collections": [{
  "name": "clients",
  "validator": {"$jsonSchema": {"bsonType": "object", "required": ["name"], "properties": {
    "name": {"bsonType": "string"},
    "city": {"bsonType": "string"}}}},
  "indexes": [{"key": {"city": 1}}]
}]}`;

export function SchemaSourcePanel({ dialect = TargetDb.SQL }) {
  const isMongo = dialect === TargetDb.MONGODB;
  const { schemaFor, applySchema, reset } = useActiveSchema();
  const schema = schemaFor(dialect);
  const [text, setText] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState(null);
  const fileInput = useRef(null);

  async function run(action) {
    setBusy(true);
    setError(null);
    try {
      applySchema(await action());
    } catch (err) {
      setError(err.response?.data?.message || err.message || 'The schema could not be uploaded.');
    } finally {
      setBusy(false);
    }
  }

  const replacesAnUpload = Boolean(schema) && !schema.builtIn;
  const warning = isMongo
    ? replacesAnUpload
      ? `Uploading replaces “${schema.label}” — that upload's collections are dropped. The built-in MongoDB demo is never touched.`
      : 'Uploaded collections are created in a separate MongoDB database, with your $jsonSchema validators and indexes, and filled with generated documents. The built-in MongoDB demo is left exactly as it is, and you can return to it at any time.'
    : replacesAnUpload
      ? `Uploading replaces “${schema.label}” — that upload's tables are dropped. The built-in demo schema is never touched.`
      : 'Uploaded tables are created in a separate database. The built-in demo schema is left exactly as it is, and you can return to it at any time.';
  const textId = `schema-text-${dialect}`;

  return (
    <div className="schema-source">
      <p className="schema-source__warning">{warning}</p>

      <div className="schema-source__actions">
        <input
          ref={fileInput}
          type="file"
          accept={isMongo ? '.json,application/json' : '.sql,text/plain'}
          className="schema-source__file"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) run(() => uploadSchemaFile(file, dialect));
            event.target.value = '';
          }}
        />
        <Button onClick={() => fileInput.current?.click()} loading={busy}>
          {isMongo ? 'Choose a .json file' : 'Choose a .sql file'}
        </Button>
        {replacesAnUpload && (
          <Button variant="ghost" onClick={() => run(() => reset(dialect))} loading={busy}>
            Back to the built-in schema
          </Button>
        )}
      </div>

      <label className="schema-source__label" htmlFor={textId}>
        {isMongo ? '…or paste a collections document (JSON)' : '…or paste CREATE TABLE statements'}
      </label>
      <textarea
        id={textId}
        className="schema-source__textarea"
        rows={10}
        spellCheck={false}
        value={text}
        placeholder={isMongo ? MONGO_PLACEHOLDER : SQL_PLACEHOLDER}
        onChange={(event) => setText(event.target.value)}
      />

      <Button
        variant="secondary"
        onClick={() => run(() => uploadSchemaText({ ddl: text, label: 'Pasted schema' }, dialect))}
        loading={busy}
        disabled={!text.trim()}
      >
        Use this schema
      </Button>

      {error && (
        <p className="schema-source__error" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}
