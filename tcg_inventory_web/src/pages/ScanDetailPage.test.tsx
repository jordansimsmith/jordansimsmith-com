import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { ScanDetailPage } from './ScanDetailPage';
import * as clientModule from '../api/client';
import type { ScanDetail, ScanRow } from '../api/client';

function row(scanPosition: number, status: ScanRow['status']): ScanRow {
  return {
    scan_position: scanPosition,
    filename: `${String(scanPosition).padStart(3, '0')}.jpg`,
    size_bytes: 4,
    uploaded: true,
    upload_url: null,
    upload_headers: null,
    status,
    needs_review: status === 'needs_review',
    suggestions: [],
    source_url: 'data:image/svg+xml,source',
    error: null,
  };
}

function detail(overrides: Partial<ScanDetail> = {}): ScanDetail {
  return {
    scan_id: 'scan-identifying',
    status: 'identifying',
    condition: 'LP',
    finish: 'foil',
    row_count: 4,
    error: null,
    import_id: null,
    created_at: 1765420932,
    rows: [
      row(1, 'suggested'),
      row(2, 'needs_review'),
      row(3, null),
      row(4, null),
    ],
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
          rows: [],
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
    expect(screen.getByText('review')).toBeDefined();
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

  it('deletes an unfinished scan through an explicit confirmation dialog', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getScan').mockResolvedValue(
      detail({ status: 'uploading', row_count: 3 }),
    );
    const deleteScan = vi
      .spyOn(clientModule.apiClient, 'deleteScan')
      .mockResolvedValue(undefined);

    renderDetailPage();

    await user.click(
      await screen.findByRole('button', { name: 'Delete scan' }),
    );
    const dialog = await screen.findByRole('dialog');
    expect(dialog.textContent).toContain('3 source cards');
    await user.click(
      within(dialog).getByRole('button', { name: 'Delete scan' }),
    );

    await waitFor(() =>
      expect(deleteScan).toHaveBeenCalledWith('scan-identifying'),
    );
    expect(screen.getByText('Scans list')).toBeDefined();
  });

  it('keeps the unfinished scan page when whole-scan deletion fails', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getScan').mockResolvedValue(
      detail({ status: 'identifying' }),
    );
    vi.spyOn(clientModule.apiClient, 'deleteScan').mockRejectedValue(
      new Error('scan deletion unavailable'),
    );

    renderDetailPage();

    await user.click(
      await screen.findByRole('button', { name: 'Delete scan' }),
    );
    const dialog = await screen.findByRole('dialog');
    await user.click(
      within(dialog).getByRole('button', { name: 'Delete scan' }),
    );

    expect(await screen.findByText('scan deletion unavailable')).toBeDefined();
    expect(
      within(screen.getByRole('dialog')).getByRole('button', {
        name: 'Delete scan',
      }),
    ).toBeDefined();
  });

  it('cancels whole-scan deletion without calling the API', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getScan').mockResolvedValue(
      detail({ status: 'reviewing', row_count: 2, rows: [] }),
    );
    const deleteScan = vi
      .spyOn(clientModule.apiClient, 'deleteScan')
      .mockResolvedValue(undefined);

    renderDetailPage();

    await user.click(
      await screen.findByRole('button', { name: 'Delete scan' }),
    );
    expect(await screen.findByRole('dialog')).toBeDefined();
    await user.keyboard('{Escape}');

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull());
    expect(deleteScan).not.toHaveBeenCalled();
    expect(screen.getByRole('heading', { name: 'Scan' })).toBeDefined();
  });

  it('renders a confirmed scan summary with its import link', async () => {
    vi.spyOn(clientModule.apiClient, 'getScan').mockResolvedValue(
      detail({
        status: 'confirmed',
        row_count: 1,
        import_id: 'import-confirmed',
        rows: [],
      }),
    );

    renderDetailPage();

    expect(
      await screen.findByRole('region', { name: 'Scan summary' }),
    ).toBeDefined();
    expect(screen.getByText('confirmed')).toBeDefined();
    expect(
      screen.getByText('This scan is confirmed and read-only.'),
    ).toBeDefined();
    expect(screen.getByRole('button', { name: 'Open import' })).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Delete scan' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Delete card' })).toBeNull();
    expect(screen.queryByRole('button', { name: 'Confirm scan' })).toBeNull();
    expect(screen.queryByRole('textbox')).toBeNull();
    expect(screen.queryByText('Confirmed cards')).toBeNull();
    expect(screen.queryByRole('img', { name: /Scanned/ })).toBeNull();
  });
});
