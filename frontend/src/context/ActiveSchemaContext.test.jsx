import { useEffect } from 'react';
import { act, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ getActiveSchema: vi.fn(), resetSchema: vi.fn() }));

import { getActiveSchema, resetSchema } from '../api';
import { ActiveSchemaProvider } from './ActiveSchemaContext';
import { useActiveSchema } from './activeSchema.js';
import { WorkbenchContext } from './workbench.js';

const SQL = { label: 'mysql demo', builtIn: true, dialect: 'MYSQL', tables: [] };
const MONGO = { label: 'mongo demo', builtIn: true, dialect: 'MONGODB', tables: [] };

const captured = { current: null };

function Probe() {
  const context = useActiveSchema();
  useEffect(() => {
    captured.current = context;
  });
  return <span>{context.schema?.label ?? 'none'}</span>;
}

function renderFor(targetDb) {
  return render(
    <WorkbenchContext.Provider value={{ targetDb }}>
      <ActiveSchemaProvider>
        <Probe />
      </ActiveSchemaProvider>
    </WorkbenchContext.Provider>,
  );
}

describe('ActiveSchemaProvider', () => {
  beforeEach(() => {
    getActiveSchema.mockReset();
    resetSchema.mockReset();
    getActiveSchema.mockImplementation(async (db) => (db === 'MONGODB' ? MONGO : SQL));
  });

  it('loads both databases and shows the one the workbench targets', async () => {
    renderFor('MONGODB');

    expect(await screen.findByText('mongo demo')).toBeInTheDocument();
    await waitFor(() => expect(captured.current.schemaFor('SQL')).toEqual(SQL));
    expect(getActiveSchema).toHaveBeenCalledWith('SQL');
    expect(getActiveSchema).toHaveBeenCalledWith('MONGODB');
  });

  it('appliedSnapshotLandsInItsOwnSlot: an uploaded MongoDB schema does not replace the MySQL one', async () => {
    renderFor('SQL');
    await screen.findByText('mysql demo');

    act(() => captured.current.applySchema({ ...MONGO, label: 'shop.json', builtIn: false }));

    expect(captured.current.schemaFor('MONGODB').label).toBe('shop.json');
    expect(screen.getByText('mysql demo')).toBeInTheDocument();
  });

  it('reset restores only the database it names', async () => {
    resetSchema.mockResolvedValue(MONGO);
    renderFor('SQL');
    await screen.findByText('mysql demo');
    act(() => captured.current.applySchema({ ...MONGO, label: 'shop.json', builtIn: false }));

    await act(async () => {
      await captured.current.reset('MONGODB');
    });

    expect(resetSchema).toHaveBeenCalledWith('MONGODB');
    expect(captured.current.schemaFor('MONGODB')).toEqual(MONGO);
    expect(captured.current.schemaFor('SQL')).toEqual(SQL);
  });

  it("a failed MongoDB load is MongoDB's error, not MySQL's", async () => {
    getActiveSchema.mockImplementation(async (db) => {
      if (db === 'MONGODB') throw new Error('MongoDB is down');
      return SQL;
    });
    renderFor('SQL');
    await screen.findByText('mysql demo');

    await waitFor(() => expect(captured.current.errorFor('MONGODB')).toBe('MongoDB is down'));
    expect(captured.current.errorFor('SQL')).toBeNull();
    expect(captured.current.error).toBeNull();
  });
});
