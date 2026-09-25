import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MongoFindingsList } from './MongoFindingsList';

const CLEAN = { hasUnindexedFilter: false, hasUnanchoredRegex: false };

describe('MongoFindingsList', () => {
  it('says the query was never run, so "no findings" is not read as "no problems"', () => {
    render(<MongoFindingsList analysis={CLEAN} />);
    expect(screen.getByText(/never run/i)).toBeInTheDocument();
  });

  it('states the limit even when it does have findings', () => {
    render(<MongoFindingsList analysis={{ ...CLEAN, hasUnanchoredRegex: true }} />);
    expect(screen.getByText(/never run/i)).toBeInTheDocument();
  });

  it('still lists the findings it has', () => {
    render(<MongoFindingsList analysis={{ ...CLEAN, hasUnanchoredRegex: true }} />);
    expect(screen.getByText(/unanchored regular expression/i)).toBeInTheDocument();
  });

  it('names the collection when the analyser recovered one', () => {
    render(
      <MongoFindingsList
        analysis={{ ...CLEAN, hasUnindexedFilter: true, filteredColumn: 'city', tableName: 'customers' }}
      />,
    );
    expect(screen.getByText(/customers/)).toBeInTheDocument();
  });

  it('does not say "null" when no collection was recovered', () => {
    render(
      <MongoFindingsList
        analysis={{ ...CLEAN, hasUnindexedFilter: true, filteredColumn: 'city', tableName: null }}
      />,
    );
    expect(screen.queryByText(/null/)).toBeNull();
  });
});
