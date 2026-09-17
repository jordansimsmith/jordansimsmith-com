import { cleanup, render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { afterEach, describe, expect, it } from 'vitest';
import { PageHeader } from './PageHeader';

describe('PageHeader', () => {
  afterEach(() => cleanup());

  it('renders the page title as the primary heading', () => {
    render(
      <MantineProvider>
        <PageHeader title="Inventory" description="Find stock" />
      </MantineProvider>,
    );

    expect(
      screen.getByRole('heading', { level: 1, name: 'Inventory' }),
    ).toBeDefined();
  });
});
