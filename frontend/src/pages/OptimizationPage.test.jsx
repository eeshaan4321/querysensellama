import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it, vi } from 'vitest';

vi.mock('../api', () => ({ adviseSchema: vi.fn(), compareQueries: vi.fn() }));

import { compareQueries } from '../api';
import { OptimizationPage } from './OptimizationPage';
import { WorkbenchContext } from '../context/workbench.js';
import { ActiveSchemaContext } from '../context/activeSchema.js';

const QUERY = 'db.customers.find({ "email": "a@b.c" }).sort({ "name": 1 })';

const MONGO_DEMO = { builtIn: true, dialect: 'MONGODB', catalog: 'querysense' };
const MYSQL_UPLOAD = { builtIn: false, dialect: 'MYSQL', catalog: 'querysense_uploads' };

function renderPage({ workbenchDb = 'MONGODB' } = {}) {
  const schemas = { MONGODB: MONGO_DEMO, SQL: MYSQL_UPLOAD };
  render(
    <MemoryRouter>
      <WorkbenchContext.Provider value={{
        build: { generatedQuery: QUERY, targetDb: 'MONGODB', naturalLanguageQuery: 'customer a@b.c by name' },
        analysis: { databaseType: 'MongoDB' },
        targetDb: workbenchDb,
        resourceLevel: 'MEDIUM',
        schemaAdvice: [],
        setSchemaAdvice: vi.fn(),
      }}>
        <ActiveSchemaContext.Provider value={{ schema: schemas[workbenchDb], schemaFor: (db) => schemas[db] }}>
          <OptimizationPage />
        </ActiveSchemaContext.Provider>
      </WorkbenchContext.Provider>
    </MemoryRouter>,
  );
}

describe('OptimizationPage', () => {
  it('compares a MongoDB query, sends the question, and calls the figures measured', async () => {
    compareQueries.mockResolvedValue({
      originalQuery: QUERY,
      optimizedQuery: 'db.customers.find({ "email": "a@b.c" })',
      rewritten: true,
      changes: [{ ruleId: 'RW3', explanation: 'Dropped .sort().' }],
      originalAnalysis: { estimatedRows: 3 },
      optimizedAnalysis: { estimatedRows: 1 },
      estimate: false,
      summary: '1 rewrite rule applied. MongoDB examined 2 fewer documents under the rewrite, measured by running both plans with explain.',
    });
    renderPage();

    expect(screen.queryByText('SQL only, for now')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Compare rewrite' }));

    await waitFor(() => expect(compareQueries).toHaveBeenCalledWith({
      query: QUERY, targetDb: 'MONGODB', userInput: 'customer a@b.c by name',
    }));
    expect(await screen.findByText('Documents examined')).toBeInTheDocument();
    expect(screen.getByText(/MongoDB examined 2 fewer documents/)).toBeInTheDocument();
    expect(screen.getByText(/measured by MongoDB/)).toBeInTheDocument();
  });

  it('reads the schema of the database the query was built for, not the toggle', () => {
    renderPage({ workbenchDb: 'SQL' });

    expect(screen.queryByText(/Anchored to/)).toBeNull();
  });
});
