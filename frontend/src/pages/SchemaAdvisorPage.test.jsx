import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ adviseSchema: vi.fn() }));

import { SchemaAdvisorPage } from './SchemaAdvisorPage';
import { WorkbenchContext } from '../context/workbench.js';
import { ActiveSchemaContext } from '../context/activeSchema.js';

function renderPage({ workbenchDb, schemas }) {
  render(
    <MemoryRouter>
      <WorkbenchContext.Provider value={{
        build: { generatedQuery: 'db.customers.find({ "city": "Mumbai" })', targetDb: 'MONGODB' },
        targetDb: workbenchDb,
        resourceLevel: 'MEDIUM',
        schemaAdvice: null,
        setSchemaAdvice: vi.fn(),
      }}>
        <ActiveSchemaContext.Provider value={{ schema: schemas[workbenchDb], schemaFor: (db) => schemas[db] }}>
          <SchemaAdvisorPage />
        </ActiveSchemaContext.Provider>
      </WorkbenchContext.Provider>
    </MemoryRouter>,
  );
}

describe('SchemaAdvisorPage', () => {
  it('reads the schema of the database the query was built for, not the toggle', () => {
    renderPage({
      workbenchDb: 'SQL',
      schemas: { SQL: { builtIn: false, dialect: 'MYSQL' }, MONGODB: { builtIn: true, dialect: 'MONGODB' } },
    });

    expect(screen.queryByText(/Anchored to/)).toBeNull();
  });

  it('words the uploaded-schema note for MongoDB', () => {
    renderPage({
      workbenchDb: 'MONGODB',
      schemas: { SQL: { builtIn: true, dialect: 'MYSQL' }, MONGODB: { builtIn: false, dialect: 'MONGODB' } },
    });

    expect(screen.getByText(/Anchored to MongoDB explain over your uploaded collections/)).toBeInTheDocument();
  });
});
