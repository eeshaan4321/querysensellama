import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { NotFoundPage } from './NotFoundPage';

function renderAt(path) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <NotFoundPage />
    </MemoryRouter>,
  );
}

describe('NotFoundPage', () => {
  it('says the page does not exist rather than rendering an empty shell', () => {
    renderAt('/about');
    expect(screen.getByText(/no page at this address/i)).toBeInTheDocument();
  });

  it('names the path that was asked for', () => {
    renderAt('/about');
    expect(screen.getByText(/\/about/)).toBeInTheDocument();
  });

  it('offers a way back to the workbench', () => {
    renderAt('/nonsense');
    expect(screen.getByRole('link', { name: /workbench/i })).toHaveAttribute('href', '/');
  });
});
