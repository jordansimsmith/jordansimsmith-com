import { render, screen, cleanup, act, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ImportDetailPage } from './ImportDetailPage';
import * as clientModule from '../api/client';
import type { ImportDetail } from '../api/client';

function importDetail(overrides: Partial<ImportDetail> = {}): ImportDetail {
  return {
    import_id: 'import-2',
    filename: 'manabox-today.csv',
    status: 'appraising',
    row_count: 40,
    keep_count: 16,
    discard_count: 3,
    review_count: 1,
    appraisal_error: null,
    created_at: 1765420932,
    rows: [],
    ...overrides,
  };
}

function renderImportDetailPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/imports/import-2']}>
        <Routes>
          <Route path="/imports/:importId" element={<ImportDetailPage />} />
          <Route path="/imports" element={<div>Imports list</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('ImportDetailPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('renders appraisal progress with running counts', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      importDetail(),
    );

    renderImportDetailPage();

    expect(await screen.findByText('manabox-today.csv')).toBeDefined();
    expect(screen.getByText('appraising')).toBeDefined();
    expect(screen.getByText('Appraising 20 of 40')).toBeDefined();
    expect(screen.getByText('Keep 16')).toBeDefined();
    expect(screen.getByText('Discard 3')).toBeDefined();
    expect(screen.getByText('Review 1')).toBeDefined();
  });

  it('polls every 2 seconds and stops when appraisal completes', async () => {
    vi.useFakeTimers();
    const getImportMock = vi
      .spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValueOnce(importDetail())
      .mockResolvedValue(
        importDetail({
          status: 'review',
          keep_count: 33,
          discard_count: 4,
          review_count: 3,
        }),
      );

    renderImportDetailPage();
    await act(async () => {});
    expect(screen.getByText('Appraising 20 of 40')).toBeDefined();
    expect(getImportMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText('review')).toBeDefined();
    expect(screen.getByText('Appraisal complete.')).toBeDefined();
    expect(screen.getByText('Keep 33')).toBeDefined();
    expect(getImportMock).toHaveBeenCalledTimes(2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(getImportMock).toHaveBeenCalledTimes(2);
  });

  it('stops polling and shows the error when appraisal fails', async () => {
    vi.useFakeTimers();
    const getImportMock = vi
      .spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValue(
        importDetail({ appraisal_error: 'FetchTCG authentication failed' }),
      );

    renderImportDetailPage();
    await act(async () => {});
    expect(screen.getByText('Appraisal failed')).toBeDefined();
    expect(screen.getByText('FetchTCG authentication failed')).toBeDefined();
    expect(screen.getByText('failed')).toBeDefined();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(6000);
    });
    expect(getImportMock).toHaveBeenCalledTimes(1);
  });

  it('shows an error with a way back when the import fails to load', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockRejectedValue(
      new Error('Not Found'),
    );

    renderImportDetailPage();

    expect(await screen.findByText('Not Found')).toBeDefined();
    await user.click(screen.getByRole('button', { name: 'Back to imports' }));
    await waitFor(() => {
      expect(screen.getByText('Imports list')).toBeDefined();
    });
  });

  it('returns to the imports list on Escape', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      importDetail({ status: 'review' }),
    );

    renderImportDetailPage();
    await screen.findByText('manabox-today.csv');

    await user.keyboard('{Escape}');

    await waitFor(() => {
      expect(screen.getByText('Imports list')).toBeDefined();
    });
  });
});
