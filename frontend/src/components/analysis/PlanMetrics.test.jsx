import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PlanMetrics } from './PlanMetrics';

describe('PlanMetrics', () => {
  it('MongoDB figures are documents, measured, with the execution time and no cost tile', () => {
    render(<PlanMetrics plan={{
      databaseType: 'MongoDB', estimatedRows: 12000, estimatedRowsReturned: 1040, filtered: 8.7, executionMillis: 9,
    }} />);

    expect(screen.getByText('Documents examined')).toBeInTheDocument();
    expect(screen.getByText('9 ms')).toBeInTheDocument();
    expect(screen.queryByText('Query cost')).toBeNull();
    expect(screen.getByText(/measured, not estimated/)).toBeInTheDocument();
  });

  it('noWasteWithoutDiscardFigure: a MongoDB group or count shows no wasted-work tile', () => {
    render(<PlanMetrics plan={{
      databaseType: 'MongoDB', estimatedRows: 2000, estimatedRowsReturned: 369, filtered: null, executionMillis: 24,
    }} />);

    expect(screen.queryByText('Wasted work')).toBeNull();
  });

  it('MySQL keeps rows, the cost tile and the estimate footnote', () => {
    render(<PlanMetrics plan={{
      databaseType: 'MySQL', estimatedRows: 300, estimatedRowsReturned: 30, queryCost: 31.45,
    }} />);

    expect(screen.getByText('Rows examined')).toBeInTheDocument();
    expect(screen.getByText('Query cost')).toBeInTheDocument();
    expect(screen.getByText(/MySQL's own estimate/, { selector: '.plan-metrics__footnote' })).toBeInTheDocument();
  });
});
