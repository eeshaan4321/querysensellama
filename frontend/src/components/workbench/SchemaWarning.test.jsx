import { render, screen } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { SchemaWarning } from './SchemaWarning';

describe('SchemaWarning', () => {
  it('renders nothing when the query matched the schema', () => {
    const { container } = render(<SchemaWarning warning={null} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the mismatch the backend reported', () => {
    render(
      <SchemaWarning warning="'transactions' is not a table in the schema that is currently active, so this query cannot run against it." />
    );
    expect(screen.getByText(/transactions/)).toBeInTheDocument();
  });

  it('says the query was not saved, so it cannot come back from the cache', () => {
    render(<SchemaWarning warning="'created_at' is not a column of 'customers'." />);
    expect(screen.getByText(/not been saved/i)).toBeInTheDocument();
  });
});
