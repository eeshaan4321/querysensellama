import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { EngineStatusBar } from './EngineStatusBar';
import { ActiveSchemaContext } from '../../context/activeSchema.js';
import { EngineStatusContext } from '../../context/engineStatus.js';

function renderWith(schema) {
  render(
    <EngineStatusContext.Provider value={{ status: null }}>
      <ActiveSchemaContext.Provider value={{ schema }}>
        <EngineStatusBar />
      </ActiveSchemaContext.Provider>
    </EngineStatusContext.Provider>,
  );
}

describe('EngineStatusBar', () => {
  it('names the built-in MongoDB demo when the workbench targets MongoDB', () => {
    renderWith({ builtIn: true, dialect: 'MONGODB', label: 'QuerySense MongoDB demo (built-in)', tables: [] });
    expect(screen.getByText('Built-in MongoDB demo')).toBeInTheDocument();
  });

  it('counts collections for an uploaded MongoDB schema', () => {
    renderWith({ builtIn: false, dialect: 'MONGODB', label: 'shop.json', catalog: 'querysense_uploads', tables: [{}, {}, {}] });
    expect(screen.getByText('shop.json · 3 collections · generated data')).toBeInTheDocument();
  });

  it('keeps the MySQL wording', () => {
    renderWith({ builtIn: true, dialect: 'MYSQL', label: 'QuerySense demo schema (built-in)', tables: [] });
    expect(screen.getByText('Built-in demo schema')).toBeInTheDocument();
  });
});
