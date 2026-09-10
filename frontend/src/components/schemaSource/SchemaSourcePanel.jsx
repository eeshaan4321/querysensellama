import { useRef, useState } from 'react';
import { Button } from '../common/Button';
import { uploadSchemaFile, uploadSchemaText } from '../../api';
import { useActiveSchema } from '../../context/ActiveSchemaContext';
import './SchemaSourcePanel.css';

const PLACEHOLDER = `CREATE TABLE clients (
  id INT PRIMARY KEY AUTO_INCREMENT,
  name VARCHAR(80),
  city VARCHAR(60)
);`;

/**
 * Both ways in: choose a .sql file, or paste DDL.
 *
 * WHY BOTH: a file picker is what someone with a mysqldump wants; a textarea
 * is what someone demoing wants, and pasting four CREATE TABLE statements
 * should not require saving a file first. They hit the same endpoint and the
 * same pipeline.
 */
export function SchemaSourcePanel() {
  const { schema, applySchema, reset } = useActiveSchema();
  const [ddl, setDdl] = useState('');
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

  return (
    <div className="schema-source">
      {/*
        Uploading drops the previous upload's tables. Said before the user
        commits to it, not after.
      */}
      <p className="schema-source__warning">
        {replacesAnUpload
          ? `Uploading replaces “${schema.label}” — that upload's tables are dropped. The built-in demo schema is never touched.`
          : 'Uploaded tables are created in a separate database. The built-in demo schema is left exactly as it is, and you can return to it at any time.'}
      </p>

      <div className="schema-source__actions">
        <input
          ref={fileInput}
          type="file"
          accept=".sql,text/plain"
          className="schema-source__file"
          onChange={(event) => {
            const file = event.target.files?.[0];
            if (file) run(() => uploadSchemaFile(file));
            // Cleared so choosing the same file twice fires onChange again.
            event.target.value = '';
          }}
        />
        <Button onClick={() => fileInput.current?.click()} loading={busy}>
          Choose a .sql file
        </Button>
        {replacesAnUpload && (
          <Button variant="ghost" onClick={() => run(reset)} loading={busy}>
            Back to the built-in schema
          </Button>
        )}
      </div>

      <label className="schema-source__label" htmlFor="schema-ddl">
        …or paste CREATE TABLE statements
      </label>
      <textarea
        id="schema-ddl"
        className="schema-source__textarea"
        rows={10}
        spellCheck={false}
        value={ddl}
        placeholder={PLACEHOLDER}
        onChange={(event) => setDdl(event.target.value)}
      />

      <Button
        variant="secondary"
        onClick={() => run(() => uploadSchemaText({ ddl, label: 'Pasted schema' }))}
        loading={busy}
        disabled={!ddl.trim()}
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
