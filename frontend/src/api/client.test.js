import { describe, expect, it } from 'vitest';
import { resolveUseMock } from './client';

describe('resolveUseMock', () => {
  it('runs against the backend when the flag is unset', () => {
    expect(resolveUseMock(undefined)).toBe(false);
  });

  it('runs against the backend when the flag is empty', () => {
    expect(resolveUseMock('')).toBe(false);
  });

  it('runs against the backend when the flag is misspelled', () => {
    expect(resolveUseMock('flase')).toBe(false);
  });

  it('runs against the backend for the literal "false"', () => {
    expect(resolveUseMock('false')).toBe(false);
  });

  it('uses mock fixtures only for the literal "true"', () => {
    expect(resolveUseMock('true')).toBe(true);
  });

  it('accepts "true" regardless of case or surrounding space', () => {
    expect(resolveUseMock('  TRUE ')).toBe(true);
  });

  it('accepts "1" as an opt in to mock fixtures', () => {
    expect(resolveUseMock('1')).toBe(true);
  });
});
