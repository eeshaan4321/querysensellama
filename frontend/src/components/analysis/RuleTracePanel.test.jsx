import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { RuleTracePanel } from './RuleTracePanel';

describe('RuleTracePanel', () => {
  it('renders nothing when there is no trace', () => {
    const { container } = render(<RuleTracePanel trace={null} handledBy="CACHE" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('names the foreign key a join was read from', () => {
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        trace={{
          outcome: 'BUILT',
          shape: 'SELECT + JOIN + GROUP BY + HAVING',
          bindings: [{ phrase: 'customers', kind: 'TABLE', resolvedTo: 'customers' }],
          joinEdge: {
            fromTable: 'orders',
            fromColumn: 'customer_id',
            toTable: 'customers',
            toColumn: 'id',
            origin: 'declared foreign key',
          },
          checksPassed: ['Exactly one declared foreign key relates these tables'],
          declineReason: null,
        }}
      />
    );

    expect(screen.getByText(/orders\.customer_id/)).toBeInTheDocument();
    expect(screen.getByText(/declared foreign key, not inferred/i)).toBeInTheDocument();
    expect(screen.getByText('SELECT + JOIN + GROUP BY + HAVING')).toBeInTheDocument();
  });

  it('shows the decline reason and which layer answered instead', () => {
    render(
      <RuleTracePanel
        handledBy="OLLAMA"
        trace={{
          outcome: 'DECLINED',
          shape: null,
          bindings: [],
          joinEdge: null,
          checksPassed: [],
          declineReason: 'The question asks about a period of time.',
        }}
      />
    );

    expect(screen.getByText(/period of time/)).toBeInTheDocument();
    expect(screen.getByText(/language model/i)).toBeInTheDocument();
  });

  it('lists what each phrase resolved to', () => {
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        trace={{
          outcome: 'BUILT',
          shape: 'SELECT',
          bindings: [{ phrase: 'pune', kind: 'FILTER', resolvedTo: 'customers.city' }],
          joinEdge: null,
          checksPassed: [],
          declineReason: null,
        }}
      />
    );

    expect(screen.getByText('customers.city')).toBeInTheDocument();
    expect(screen.getByText('FILTER')).toBeInTheDocument();
  });

  it('shows how the engine read words the schema does not use', () => {
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        interpretations={[
          { phrase: 'clients', item: 'customers', kind: 'SYNONYM' },
          { phrase: 'emial', item: 'customers.email', kind: 'TYPO' },
          { phrase: 'buyers', item: 'customers', kind: 'SCHEMA_ALIAS' },
        ]}
        trace={{ outcome: 'BUILT', shape: 'SELECT', bindings: [], joinEdge: null, checksPassed: [], declineReason: null }}
      />
    );

    expect(screen.getByText('Read as')).toBeInTheDocument();
    expect(screen.getByText('built-in synonym')).toBeInTheDocument();
    expect(screen.getByText('one letter off')).toBeInTheDocument();
    expect(screen.getByText('alias declared by the schema')).toBeInTheDocument();
  });

  it('lists every edge of a multi-table chain', () => {
    const edge = (fromTable, fromColumn, toTable) => ({ fromTable, fromColumn, toTable, toColumn: 'id', origin: 'declared foreign key' });
    render(
      <RuleTracePanel
        handledBy="RULE_ENGINE"
        trace={{
          outcome: 'BUILT', shape: 'SELECT + JOIN', bindings: [], checksPassed: [], declineReason: null,
          joinEdge: edge('orders', 'customer_id', 'customers'),
          joinEdges: [edge('orders', 'customer_id', 'customers'), edge('order_items', 'order_id', 'orders')],
        }}
      />
    );

    expect(screen.getByText('order_items.order_id')).toBeInTheDocument();
    expect(screen.getByText('orders.customer_id')).toBeInTheDocument();
  });
});
