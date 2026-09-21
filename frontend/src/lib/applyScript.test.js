import { describe, it, expect } from 'vitest';
import { buildApplyScript } from './applyScript';

const indexRec = {
  type: 'INDEX',
  targetTable: 'customers',
  targetColumn: 'city',
  summary: 'The database is reading all 100 rows of customers and checking each one, because nothing indexes city.',
  reason: 'Full table scan detected (accessType=ALL) with no index in use.',
  suggestedAction: 'CREATE INDEX idx_customers_city ON customers(city);',
};

const structuralRec = {
  type: 'STRUCTURAL',
  targetTable: 'orders',
  targetColumn: null,
  summary: 'The database is building a scratch working table behind the scenes to combine these rows.',
  reason: "Query requires a temporary table (Extra: 'Using temporary').",
  suggestedAction:
    "Review the GROUP BY/DISTINCT clause on 'orders'; consider a composite index covering the grouped column(s).",
};

const mongoRec = {
  type: 'INDEX',
  targetTable: 'orders',
  targetColumn: 'status',
  summary: 'The database is checking every record in this collection, because nothing indexes status.',
  reason: "Filtering on 'status' with no known index.",
  suggestedAction: 'db.orders.createIndex({ "status": 1 });',
};

describe('buildApplyScript', () => {
  it('returns nothing runnable for an empty recommendation list', () => {
    const result = buildApplyScript([]);
    expect(result.runnableCount).toBe(0);
    expect(result.reviewCount).toBe(0);
    expect(result.script).toBe('');
  });

  it('emits a CREATE INDEX statement verbatim as runnable', () => {
    const result = buildApplyScript([indexRec]);
    expect(result.runnableCount).toBe(1);
    expect(result.reviewCount).toBe(0);
    expect(result.script).toContain('CREATE INDEX idx_customers_city ON customers(city);');
  });

  it('comments out STRUCTURAL prose so the script stays paste-safe', () => {
    const result = buildApplyScript([structuralRec]);
    expect(result.runnableCount).toBe(0);
    expect(result.reviewCount).toBe(1);

    const executable = result.script
      .split('\n')
      .filter((line) => line.trim() && !line.trim().startsWith('--'));
    expect(executable).toEqual([]);
  });

  it('keeps runnable statements above commented review notes when mixed', () => {
    const result = buildApplyScript([structuralRec, indexRec]);
    expect(result.runnableCount).toBe(1);
    expect(result.reviewCount).toBe(1);

    const ddlLine = result.script.indexOf('CREATE INDEX');
    const reviewLine = result.script.indexOf('REVIEW');
    expect(ddlLine).toBeGreaterThan(-1);
    expect(reviewLine).toBeGreaterThan(ddlLine);
  });

  it('never emits a placeholder column as a runnable statement', () => {
    const placeholderRec = {
      type: 'INDEX',
      targetTable: 'customers',
      targetColumn: null,
      reason: 'Full table scan detected (accessType=ALL) with no index in use.',
      suggestedAction:
        'CREATE INDEX idx_customers_col ON customers(<the column used in the WHERE clause>);',
    };

    const result = buildApplyScript([placeholderRec]);
    expect(result.runnableCount).toBe(0);
    expect(result.reviewCount).toBe(1);

    const executable = result.script
      .split('\n')
      .filter((line) => line.trim() && !line.trim().startsWith('--'));
    expect(executable).toEqual([]);
  });

  it('switches to // comments and db. detection for MongoDB', () => {
    const result = buildApplyScript([mongoRec], { databaseType: 'MongoDB' });
    expect(result.dialect).toBe('MONGODB');
    expect(result.runnableCount).toBe(1);
    expect(result.script).toContain('db.orders.createIndex({ "status": 1 });');
    expect(result.script).toContain('//');
    expect(result.script).not.toContain('--');
  });

  it('never emits a Mongo placeholder collection as runnable', () => {
    const result = buildApplyScript(
      [{ ...mongoRec, targetTable: null, suggestedAction: 'db.<collection>.createIndex({ "status": 1 });' }],
      { databaseType: 'MongoDB' }
    );
    expect(result.runnableCount).toBe(0);
    expect(result.reviewCount).toBe(1);
  });

  it('names the catalog of an uploaded schema in the header', () => {
    const result = buildApplyScript([indexRec], { catalog: 'querysense_uploads' });
    expect(result.script).toContain('querysense_uploads');
  });

  it('falls back to "the connected database" when catalog is null', () => {
    const result = buildApplyScript([indexRec], { catalog: null });
    expect(result.script).toContain('the connected database');
  });

  it('always carries the overlap caveat, since the advisor has no consistency check', () => {
    const result = buildApplyScript([indexRec]);
    expect(result.script.toLowerCase()).toContain('overlap');
  });

  it('comments each statement with the plain summary, not the technical reason', () => {
    const result = buildApplyScript([indexRec]);
    expect(result.script).toContain('nothing indexes city');
    expect(result.script).not.toContain('accessType=ALL');
  });

  it('falls back to the technical reason when a summary is absent', () => {
    const { summary, ...noSummary } = indexRec;
    void summary;
    const result = buildApplyScript([noSummary]);
    expect(result.script).toContain('accessType=ALL');
  });
});
