import { render, screen, within } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { PlanTreeView } from './PlanTreeView';

const JOIN_TREE = {
  kind: 'query_block',
  label: 'query block',
  cost: 412.88,
  children: [
    {
      kind: 'grouping_operation',
      label: 'grouping operation',
      usesFilesort: true,
      usesTemporaryTable: true,
      children: [
        {
          kind: 'nested_loop',
          label: 'nested loop',
          children: [
            { kind: 'table', label: 'c', accessType: 'ALL', estimatedRows: 303, children: [] },
            {
              kind: 'table',
              label: 'o',
              accessType: 'ref',
              keyUsed: 'idx_customer_id',
              estimatedRows: 4,
              children: [],
            },
          ],
        },
      ],
    },
  ],
};

describe('PlanTreeView', () => {
  it('says so plainly when there is no tree, rather than rendering a blank box', () => {
    render(<PlanTreeView tree={null} />);
    expect(screen.getByText(/no plan tree/i)).toBeInTheDocument();
  });

  it('renders every node in the tree', () => {
    render(<PlanTreeView tree={JOIN_TREE} />);
    expect(screen.getByText('query block')).toBeInTheDocument();
    expect(screen.getByText('grouping operation')).toBeInTheDocument();
    expect(screen.getByText('nested loop')).toBeInTheDocument();
    expect(screen.getByText('c')).toBeInTheDocument();
    expect(screen.getByText('o')).toBeInTheDocument();
  });

  it('nests children inside their parent, which is the whole point of a tree', () => {
    render(<PlanTreeView tree={JOIN_TREE} />);
    const grouping = screen.getByText('grouping operation').closest('li');
    expect(within(grouping).getByText('nested loop')).toBeInTheDocument();
    expect(within(grouping).getByText('c')).toBeInTheDocument();
  });

  it('shows a table node access type and row estimate', () => {
    render(<PlanTreeView tree={JOIN_TREE} />);
    const table = screen.getByText('c').closest('li');
    expect(within(table).getByText(/ALL/)).toBeInTheDocument();
    expect(within(table).getByText(/303/)).toBeInTheDocument();
  });

  it('names the index a table used', () => {
    render(<PlanTreeView tree={JOIN_TREE} />);
    const table = screen.getByText('o').closest('li');
    expect(within(table).getByText(/idx_customer_id/)).toBeInTheDocument();
  });

  it('attributes a filesort to the operation that does it, not the tables under it', () => {
    render(<PlanTreeView tree={JOIN_TREE} />);
    const grouping = screen.getByText('grouping operation').closest('li');
    expect(within(grouping).getAllByText(/filesort/i).length).toBeGreaterThan(0);

    const table = screen.getByText('c').closest('li');
    expect(within(table).queryByText(/filesort/i)).toBeNull();
  });

  it('renders a single-node tree without crashing on missing children', () => {
    render(<PlanTreeView tree={{ kind: 'query_block', label: 'query block' }} />);
    expect(screen.getByText('query block')).toBeInTheDocument();
  });
});
