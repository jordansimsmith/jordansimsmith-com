import { render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { describe, it, expect } from 'vitest';
import { App } from './App';

describe('App', () => {
  it('renders the title', () => {
    render(
      <MantineProvider>
        <App />
      </MantineProvider>,
    );

    expect(
      screen.getByRole('heading', { name: /packing list/i }),
    ).toBeDefined();
  });
});
