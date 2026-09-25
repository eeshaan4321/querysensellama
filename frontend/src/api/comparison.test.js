import { describe, expect, it } from 'vitest';
import { toCompareRequest } from './comparison';
import { TargetDb } from './contracts';

describe('toCompareRequest', () => {
  it('maps the SQL target to the backend literal', () => {
    expect(toCompareRequest({ query: 'SELECT 1;', targetDb: TargetDb.SQL }))
      .toEqual({ query: 'SELECT 1;', databaseType: 'MySQL' });
  });

  it('maps the MongoDB target to the backend literal', () => {
    expect(toCompareRequest({ query: 'db.c.find({})', targetDb: TargetDb.MONGODB }))
      .toEqual({ query: 'db.c.find({})', databaseType: 'MongoDB' });
  });

  it('defaults to MySQL when no target is given', () => {
    expect(toCompareRequest({ query: 'SELECT 1;' }).databaseType).toBe('MySQL');
  });

  it('sends the query verbatim, preserving case in filter values', () => {
    const query = "SELECT * FROM customers WHERE city = 'Mumbai';";
    expect(toCompareRequest({ query, targetDb: TargetDb.SQL }).query).toBe(query);
  });
});
