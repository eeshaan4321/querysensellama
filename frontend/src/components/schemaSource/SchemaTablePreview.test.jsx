import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { SchemaTablePreview } from './SchemaTablePreview';

const MONGO = {
  label: 'shop.json',
  builtIn: false,
  catalog: 'querysense_uploads',
  dialect: 'MONGODB',
  warnings: [],
  tables: [{
    name: 'orders',
    columns: ['_id', 'customer_id', 'items.quantity'],
    rowCount: 12000,
    embeddedArrays: ['items'],
    fieldTypes: { _id: 'objectId', customer_id: 'objectId', 'items.quantity': 'int' },
  }],
  foreignKeys: [{ fromTable: 'orders', fromColumn: 'customer_id', toTable: 'customers', toColumn: '_id' }],
  indexes: [{ table: 'orders', keys: [{ column: 'customer_id', direction: 1 }], unique: false }],
};

const MYSQL = {
  label: 'shop.sql',
  builtIn: false,
  catalog: 'querysense_uploads',
  dialect: 'MYSQL',
  warnings: [],
  tables: [{ name: 'customers', columns: ['id', 'name'], rowCount: 500, embeddedArrays: [], fieldTypes: { id: 'INT' } }],
  foreignKeys: [],
  indexes: [],
};

describe('SchemaTablePreview', () => {
  it('a MongoDB schema shows typed paths, embedded arrays, references, indexes and document counts', () => {
    render(<SchemaTablePreview schema={MONGO} />);

    expect(screen.getByText('1 collection · querysense_uploads')).toBeInTheDocument();
    expect(screen.getByText('items.quantity')).toBeInTheDocument();
    expect(screen.getByText('int')).toBeInTheDocument();
    expect(screen.getByText('Embedded array: items')).toBeInTheDocument();
    expect(screen.getByText('customer_id → customers._id')).toBeInTheDocument();
    expect(screen.getByText('index { customer_id: 1 }')).toBeInTheDocument();
    expect(screen.getByText('12,000 documents')).toBeInTheDocument();
  });

  it('a MySQL schema renders as before, with no type labels', () => {
    render(<SchemaTablePreview schema={MYSQL} />);

    expect(screen.getByText('1 table · querysense_uploads')).toBeInTheDocument();
    expect(screen.getByText('500 rows')).toBeInTheDocument();
    expect(screen.queryByText('INT')).toBeNull();
  });
});
