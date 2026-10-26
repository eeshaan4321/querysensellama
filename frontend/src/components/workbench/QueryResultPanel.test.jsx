import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, it, expect } from 'vitest';
import { QueryResultPanel } from './QueryResultPanel';

const refused = {
  generatedQuery: null,
  targetDb: 'MySQL',
  handledBy: 'RULE_ENGINE',
  confidence: 0,
  ruleTrace: {
    outcome: 'REFUSED',
    declineReason: 'The question asks to remove a whole table. QuerySense does not suggest DROP or TRUNCATE.',
  },
  schemaWarning: null,
};

describe('QueryResultPanel', () => {
  it('shows a refusal with its reason instead of a query', () => {
    render(<MemoryRouter><QueryResultPanel result={refused} targetDb="MySQL" /></MemoryRouter>);
    expect(screen.getByText(/No query generated/)).toBeInTheDocument();
    expect(screen.getByText(/does not suggest DROP or TRUNCATE/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Analyze this query/ })).not.toBeInTheDocument();
  });
});
