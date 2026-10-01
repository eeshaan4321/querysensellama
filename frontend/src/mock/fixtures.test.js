import { describe, expect, it } from 'vitest';
import {
  mockActiveSchema, mockAnalyzeQuery, mockCompare, mockResetSchema, mockSchemaUpload,
} from './fixtures';

describe('MongoDB mock fixtures', () => {
  it('the MongoDB demo has collections, typed paths, an embedded array, references and indexes', () => {
    const schema = mockActiveSchema('MONGODB');
    const orders = schema.tables.find((t) => t.name === 'orders');

    expect(schema.dialect).toBe('MONGODB');
    expect(orders.embeddedArrays).toEqual(['items']);
    expect(orders.fieldTypes['items.quantity']).toBe('int');
    expect(schema.foreignKeys).toContainEqual(
      { fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: '_id' });
    expect(schema.indexes).toContainEqual(
      { table: 'customers', keys: [{ column: 'email', direction: 1 }], unique: true });
  });

  it('each database keeps its own active schema', () => {
    mockSchemaUpload({
      ddl: '{"collections": [{"name": "logs", "validator": {"$jsonSchema": {"properties": {"level": {"bsonType": "string"}}}}}]}',
      label: 'logs.json',
      targetDb: 'MONGODB',
    });

    expect(mockActiveSchema('MONGODB').label).toBe('logs.json');
    expect(mockActiveSchema('MONGODB').tables[0].fieldTypes).toEqual({ _id: 'objectId', level: 'string' });
    expect(mockActiveSchema('SQL').builtIn).toBe(true);
    expect(mockResetSchema('MONGODB').builtIn).toBe(true);
  });

  it('a MongoDB analysis is shaped like real explain output', () => {
    const { analysis, suggestions } = mockAnalyzeQuery({
      query: 'db.customers.find({ "city": "Pune" })', targetDb: 'MONGODB', resourceLevel: 'MEDIUM',
    });

    expect(analysis.databaseType).toBe('MongoDB');
    expect(analysis.accessType).toBe('COLLSCAN');
    expect(analysis.planTree.kind).toBe('COLLSCAN');
    expect(analysis.queryCost).toBeNull();
    expect(typeof analysis.executionMillis).toBe('number');
    expect(analysis.verdict.headline).toContain('collection');
    expect(suggestions[0].suggestedChange).toBe('db.customers.createIndex({ "city": 1 });');
  });

  it('a MongoDB comparison is measured, not estimated', () => {
    const result = mockCompare({
      query: 'db.customers.find({ "email": "a@b.c" }).sort({ "name": 1 })', targetDb: 'MONGODB',
    });

    expect(result.rewritten).toBe(true);
    expect(result.estimate).toBe(false);
    expect(result.optimizedQuery).not.toContain('.sort(');
    expect(result.summary).toContain('MongoDB examined');
  });
});
