import { render, screen, cleanup } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { describe, it, expect, afterEach } from 'vitest';
import { JobFailureAlert } from './JobFailureAlert';

function renderAlert(error: string | null) {
  return render(
    <MantineProvider>
      <JobFailureAlert title="Publish failed" error={error} />
    </MantineProvider>,
  );
}

describe('JobFailureAlert', () => {
  afterEach(() => {
    cleanup();
  });

  it('renders the title and message', () => {
    renderAlert('FetchTCG request failed with status 500');

    expect(screen.getByText('Publish failed')).toBeDefined();
    expect(
      screen.getByText('FetchTCG request failed with status 500'),
    ).toBeDefined();
  });

  it('renders the title alone when there is no error message', () => {
    renderAlert(null);

    expect(screen.getByText('Publish failed')).toBeDefined();
  });
});
