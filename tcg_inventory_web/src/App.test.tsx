import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { describe, it, expect } from 'vitest';
import { App } from './App';

describe('App', () => {
  it('renders the login page', () => {
    render(
      <MantineProvider>
        <App />
      </MantineProvider>,
    );

    expect(
      screen.getByRole('heading', { name: /tcg inventory/i }),
    ).toBeDefined();
  });
});
