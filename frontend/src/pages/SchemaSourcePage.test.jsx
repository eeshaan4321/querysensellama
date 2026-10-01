import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ uploadSchemaFile: vi.fn(), uploadSchemaText: vi.fn() }));

import { uploadSchemaText } from '../api';
import { SchemaSourcePage } from './SchemaSourcePage';
import { ActiveSchemaContext } from '../context/activeSchema.js';
import { WorkbenchContext } from '../context/workbench.js';

const SQL = {
  label: 'QuerySense demo schema (built-in)', builtIn: true, catalog: null, dialect: 'MYSQL', warnings: [],
  tables: [{ name: 'customers', columns: ['id', 'name'], rowCount: 0 }], foreignKeys: [], indexes: [],
};
const MONGO = {
  label: 'QuerySense MongoDB demo (built-in)', builtIn: true, catalog: 'querysense', dialect: 'MONGODB', warnings: [],
  tables: [{ name: 'orders', columns: ['_id'], rowCount: 0, embeddedArrays: [], fieldTypes: { _id: 'objectId' } }],
  foreignKeys: [], indexes: [],
};

function renderPage(targetDb) {
  const value = {
    schemaFor: (db) => (db === 'MONGODB' ? MONGO : SQL),
    applySchema: vi.fn(),
    reset: vi.fn(),
    loading: false,
    error: null,
  };
  render(
    <WorkbenchContext.Provider value={{ targetDb }}>
      <ActiveSchemaContext.Provider value={value}>
        <SchemaSourcePage />
      </ActiveSchemaContext.Provider>
    </WorkbenchContext.Provider>,
  );
  return value;
}

describe('SchemaSourcePage', () => {
  it('opens on the database the workbench targets', () => {
    renderPage('MONGODB');

    expect(screen.getByRole('tab', { name: 'MongoDB' })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('button', { name: 'Choose a .json file' })).toBeInTheDocument();
    expect(screen.getByText('QuerySense MongoDB demo (built-in)')).toBeInTheDocument();
  });

  it('switching to MongoDB shows its upload and its schema', () => {
    renderPage('SQL');
    expect(screen.getByRole('button', { name: 'Choose a .sql file' })).toBeInTheDocument();

    fireEvent.click(screen.getByRole('tab', { name: 'MongoDB' }));

    expect(screen.getByRole('button', { name: 'Choose a .json file' })).toBeInTheDocument();
    expect(screen.getByText('QuerySense MongoDB demo (built-in)')).toBeInTheDocument();
  });

  it('pastedTextUploadsForSelectedDatabase: a pasted document goes to MongoDB from the MongoDB tab', async () => {
    uploadSchemaText.mockResolvedValue(MONGO);
    const context = renderPage('MONGODB');

    fireEvent.change(screen.getByLabelText(/paste a collections document/i), {
      target: { value: '{"collections": []}' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Use this schema' }));

    await waitFor(() => expect(uploadSchemaText)
      .toHaveBeenCalledWith({ ddl: '{"collections": []}', label: 'Pasted schema' }, 'MONGODB'));
    await waitFor(() => expect(context.applySchema).toHaveBeenCalledWith(MONGO));
  });
});
