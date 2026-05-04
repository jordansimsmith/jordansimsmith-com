import { render, screen, cleanup } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { App } from './App';

describe('App', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders the login page when no session is present', () => {
    render(
      <MantineProvider>
        <App />
      </MantineProvider>,
    );

    expect(
      screen.getByRole('heading', { name: /book tracker/i }),
    ).toBeDefined();
    expect(screen.getByLabelText(/username/i)).toBeDefined();
  });
});
