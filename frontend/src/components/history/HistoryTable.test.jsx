import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { HistoryTable } from './HistoryTable';

const RECORD = {
  id: 1,
  userInput: 'show customers from mumbai',
  generatedQuery: "SELECT * FROM customers WHERE city = 'mumbai';",
  source: 'RULE_ENGINE',
  confidence: 0.95,
  durationMs: 12,
  schemaLabel: 'shop.sql',
  createdAt: '2026-09-21T10:00:00',
};

function rowFor(record) {
  render(<HistoryTable records={[record]} />);
  return screen.getByRole('row', { name: /show customers/i });
}

describe('HistoryTable', () => {
  it('shows the confidence the run was labelled with', () => {
    expect(within(rowFor(RECORD)).getByText('95%')).toBeInTheDocument();
  });

  it('shows which schema was live, since uploads swap it between runs', () => {
    expect(within(rowFor(RECORD)).getByText('shop.sql')).toBeInTheDocument();
  });

  it('shows how long the run took', () => {
    expect(within(rowFor(RECORD)).getByText('12 ms')).toBeInTheDocument();
  });

  it('renders an older row with no run context without inventing values', () => {
    const legacy = { ...RECORD, confidence: null, durationMs: null, schemaLabel: null };
    const row = rowFor(legacy);

    expect(within(row).queryByText('95%')).toBeNull();
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(3);
  });

  it('does not round a low confidence up to a reassuring number', () => {
    expect(within(rowFor({ ...RECORD, confidence: 0.2 })).getByText('20%')).toBeInTheDocument();
  });

  it('shows the resource level the run was made under', () => {
    expect(within(rowFor({ ...RECORD, resourceLevel: 'LOW' })).getByText('Low')).toBeInTheDocument();
  });

  it('shows a dash for a run recorded before resource level was stored', () => {
    const row = rowFor({ ...RECORD, resourceLevel: null, confidence: null, durationMs: null, schemaLabel: null });
    expect(within(row).getAllByText('—').length).toBeGreaterThanOrEqual(4);
  });

  it('shows which database the query was written for', () => {
    expect(within(rowFor({ ...RECORD, databaseType: 'MongoDB' })).getByText('MongoDB')).toBeInTheDocument();
  });

  it('has a Database column', () => {
    render(<HistoryTable records={[RECORD]} />);
    expect(screen.getByRole('columnheader', { name: 'Database' })).toBeInTheDocument();
  });
});
