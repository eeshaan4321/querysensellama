import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SuggestionsList } from './SuggestionsList';

describe('SuggestionsList', () => {
  it('shows the statement a suggestion carries as its fix', () => {
    render(<SuggestionsList suggestions={[{
      message: 'Collection scan on customers.', severity: 'CRITICAL', source: 'DOMAIN',
      suggestedChange: 'db.customers.createIndex({ "city": 1 });',
    }]} />);

    expect(screen.getByText('db.customers.createIndex({ "city": 1 });')).toBeInTheDocument();
  });
});
