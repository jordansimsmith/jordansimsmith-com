import { act, cleanup, render, screen } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScanDetailPage } from './ScanDetailPage';
import * as clientModule from '../api/client';
import type { ScanDetail } from '../api/client';

function detail(overrides: Partial<ScanDetail> = {}): ScanDetail {
  return {
    scan_id: 'scan-identifying',
    status: 'identifying',
    condition: 'LP',
    finish: 'foil',
    row_count: 4,
    processed_count: 2,
    error: null,
    import_id: null,
    created_at: 1765420932,
    rows: [],
    ...overrides,
  };
}

function renderDetailPage() {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={['/scans/scan-identifying']}>
        <Routes>
          <Route path="/scans/:scanId" element={<ScanDetailPage />} />
          <Route path="/scans" element={<div>Scans list</div>} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('ScanDetailPage', () => {
  afterEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.useRealTimers();
  });

  it('renders identification progress', async () => {
    vi.spyOn(clientModule.apiClient, 'getScan').mockResolvedValue(detail());

    renderDetailPage();

    expect(await screen.findByText('Identifying 2 of 4')).toBeDefined();
    expect(
      screen.getByRole('progressbar', { name: 'Identification progress' }),
    ).toBeDefined();
    expect(screen.getByText('LP')).toBeDefined();
    expect(screen.getByText('Foil')).toBeDefined();
  });

  it('polls while identifying and stops when identification completes', async () => {
    vi.useFakeTimers();
    const getScan = vi
      .spyOn(clientModule.apiClient, 'getScan')
      .mockResolvedValueOnce(detail())
      .mockResolvedValue(
        detail({
          status: 'reviewing',
          processed_count: 4,
        }),
      );

    renderDetailPage();
    await act(async () => {
      await Promise.resolve();
    });
    expect(getScan).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(getScan).toHaveBeenCalledTimes(2);
    expect(screen.getByRole('heading', { name: 'Scan' })).toBeDefined();
    expect(screen.getByRole('region', { name: 'Scan summary' })).toBeDefined();
    expect(
      screen.getByRole('progressbar', { name: 'Review confirmation progress' }),
    ).toBeDefined();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(4000);
    });
    expect(getScan).toHaveBeenCalledTimes(2);
  });

  it('renders an abandoned uploading scan as read-only', async () => {
    vi.spyOn(clientModule.apiClient, 'getScan').mockResolvedValue(
      detail({
        status: 'uploading',
        processed_count: 0,
      }),
    );

    renderDetailPage();

    expect(
      await screen.findByText(
        'This upload is incomplete and cannot be resumed.',
      ),
    ).toBeDefined();
    expect(
      screen.queryByRole('button', { name: /retry|resume|upload/i }),
    ).toBeNull();
  });

  it('shows a load error', async () => {
    vi.spyOn(clientModule.apiClient, 'getScan').mockRejectedValue(
      new Error('scan unavailable'),
    );

    renderDetailPage();

    expect(await screen.findByText('scan unavailable')).toBeDefined();
    expect(screen.getByText('Scan could not be loaded')).toBeDefined();
  });
});
