import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BackendHealthBanner } from './BackendHealthBanner';

describe('BackendHealthBanner', () => {
  it('stays out of the way while the backend is up', () => {
    const { container } = render(<BackendHealthBanner status="up" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('stays out of the way before the first probe returns', () => {
    const { container } = render(<BackendHealthBanner status="unknown" />);
    expect(container).toBeEmptyDOMElement();
  });

  it('names the backend and the port when it is down', () => {
    render(<BackendHealthBanner status="down" baseUrl="http://localhost:8080" />);
    expect(screen.getByRole('alert')).toHaveTextContent(/backend/i);
    expect(screen.getByRole('alert')).toHaveTextContent('http://localhost:8080');
  });

  it('says the screens are empty rather than wrong', () => {
    render(<BackendHealthBanner status="down" />);
    expect(screen.getByRole('alert')).toHaveTextContent(/not showing stale or mock data/i);
  });

  it('offers a retry that re-runs the probe', async () => {
    const onRetry = vi.fn();
    render(<BackendHealthBanner status="down" onRetry={onRetry} />);
    screen.getByRole('button', { name: /retry/i }).click();
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('announces mock mode separately, because that is not a failure', () => {
    render(<BackendHealthBanner status="mock" />);
    expect(screen.getByRole('status')).toHaveTextContent(/mock/i);
  });
});
