import { render, screen } from '@testing-library/react';
import { beforeAll, describe, expect, it, vi } from 'vitest';
import { ExplainPlanView } from './ExplainPlanView';
import { ActiveSchemaContext } from '../../context/activeSchema.js';

beforeAll(() => {
  window.matchMedia ??= vi.fn(() => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }));
});

const MONGO_UPLOAD = { builtIn: false, catalog: 'querysense_uploads', dialect: 'MONGODB' };

function renderPlan(plan, schema = null) {
  render(
    <ActiveSchemaContext.Provider value={{ schemaFor: () => schema }}>
      <ExplainPlanView plan={plan} />
    </ActiveSchemaContext.Provider>,
  );
}

const MONGO_PLAN = {
  databaseType: 'MongoDB',
  explainFailed: false,
  estimatedRows: 12000,
  estimatedRowsReturned: 1040,
  filtered: 8.7,
  executionMillis: 9,
  tables: [{
    tableName: 'customers', accessType: 'COLLSCAN', keyUsed: null, possibleKeys: null,
    estimatedRows: 12000, estimatedRowsReturned: 1040, fullTableScan: true, usesIndex: false,
    usesFilesort: false, usesTemporaryTable: false,
  }],
};

describe('ExplainPlanView', () => {
  it('a MongoDB plan speaks of collections, documents and plan stages', () => {
    renderPlan(MONGO_PLAN, MONGO_UPLOAD);

    expect(screen.getAllByText('Plan stage').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Documents examined').length).toBeGreaterThan(0);
    expect(screen.getByText('Collection scan', { selector: '.explain-plan__table-badge' })).toBeInTheDocument();
    expect(screen.getByText(/This plan is MongoDB's/)).toBeInTheDocument();
    expect(screen.queryByText('Joined on')).toBeNull();
    expect(screen.queryByText(/MySQL/)).toBeNull();
  });

  it('mysqlLabelsUnchanged: a MySQL plan keeps its labels', () => {
    renderPlan({
      databaseType: 'MySQL',
      explainFailed: false,
      tables: [{ tableName: 'customers', accessType: 'ALL', estimatedRows: 300, fullTableScan: true }],
    });

    expect(screen.getAllByText('Access type').length).toBeGreaterThan(0);
    expect(screen.getAllByText('Rows examined').length).toBeGreaterThan(0);
    expect(screen.getByText('Joined on')).toBeInTheDocument();
    expect(screen.getByText('Full scan')).toBeInTheDocument();
  });

  it('a failed MongoDB explain says explain, not EXPLAIN', () => {
    renderPlan({ databaseType: 'MongoDB', explainFailed: true, tables: [] });

    expect(screen.getAllByText('Unavailable — explain failed').length).toBeGreaterThan(0);
  });
});
