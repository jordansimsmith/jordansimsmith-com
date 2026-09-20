import { cleanup, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, describe, expect, it } from 'vitest';
import { AppShellLayout } from './AppShellLayout';

function renderShell(pathname: string) {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={[pathname]}>
        <AppShellLayout>
          <main>Page content</main>
        </AppShellLayout>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('AppShellLayout', () => {
  afterEach(() => cleanup());

  it('keeps detail routes highlighted in the main navigation', () => {
    renderShell('/inventory/a-card');

    expect(
      screen
        .getByRole('link', { name: 'Inventory' })
        .getAttribute('aria-current'),
    ).toBe('page');
    expect(
      screen
        .getByRole('link', { name: 'Imports' })
        .getAttribute('aria-current'),
    ).toBeNull();
    expect(
      screen.getByRole('link', { name: 'Scans' }).getAttribute('aria-current'),
    ).toBeNull();
    expect(screen.getByText('Workspace')).toBeDefined();
  });

  it('highlights the scan navigation item on the scan route', () => {
    renderShell('/scan');

    expect(
      screen.getByRole('link', { name: 'Scans' }).getAttribute('aria-current'),
    ).toBe('page');
  });

  it('closes the mobile navigation after a destination is selected', async () => {
    const user = userEvent.setup();
    renderShell('/inventory');

    await user.click(screen.getByRole('button', { name: 'Open navigation' }));
    expect(
      screen
        .getByRole('button', { name: 'Close navigation' })
        .getAttribute('aria-expanded'),
    ).toBe('true');

    await user.click(screen.getByRole('link', { name: 'Orders' }));
    expect(
      screen
        .getByRole('button', { name: 'Open navigation' })
        .getAttribute('aria-expanded'),
    ).toBe('false');
  });
});
