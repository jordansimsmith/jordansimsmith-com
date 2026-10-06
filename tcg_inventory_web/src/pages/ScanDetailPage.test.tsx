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
import { GamesProvider } from '../GamesProvider';
import * as clientModule from '../api/client';
import type { CatalogCard, ScanDetail, ScanRow } from '../api/client';

const REGISTERED_GAMES = [
  {
    id: 'mtg',
    display_name: 'Magic: The Gathering',
    scanning_enabled: true,
    csv_import_enabled: true,
    scan_review_image_regions: [],
    finishes: [
      { id: 'normal', display_name: 'Normal' },
      { id: 'foil', display_name: 'Foil' },
      { id: 'etched', display_name: 'Etched' },
    ],
  },
];

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
    game: 'mtg',
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

const suggestedProduct: CatalogCard = {
  game: 'mtg',
  external_id: 'product-1',
  name: 'Lightning Bolt',
  set_code: '2x2',
  set_name: 'Double Masters 2022',
  collector_number: '117',
  image_urls: { small: null, normal: null },
  available_finishes: ['foil'],
};

function reviewableDetail(overrides: Partial<ScanDetail> = {}): ScanDetail {
  return detail({
    status: 'reviewing',
    row_count: 1,
    rows: [row(1, 'suggested')].map((scanRow) => ({
      ...scanRow,
      suggestions: [
        {
          external_id: suggestedProduct.external_id,
          name: suggestedProduct.name,
          score: 0.9,
        },
      ],
    })),
    ...overrides,
  });
}

function renderDetailPage() {
  return render(
    <MantineProvider>
      <GamesProvider initialGames={REGISTERED_GAMES}>
        <MemoryRouter initialEntries={['/scans/scan-identifying']}>
          <Routes>
            <Route path="/scans/:scanId" element={<ScanDetailPage />} />
            <Route path="/scans" element={<div>Scans list</div>} />
            <Route path="/imports/:importId" element={<div>Import page</div>} />
          </Routes>
        </MemoryRouter>
      </GamesProvider>
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

  it('polls after accepting confirmation and opens the import when confirmed', async () => {
    const user = userEvent.setup();
    const getScan = vi
      .spyOn(clientModule.apiClient, 'getScan')
      .mockResolvedValueOnce(reviewableDetail())
      .mockResolvedValueOnce(reviewableDetail({ status: 'confirming' }))
      .mockResolvedValue(
        reviewableDetail({
          status: 'confirmed',
          import_id: 'import-confirmed',
        }),
      );
    vi.spyOn(clientModule.apiClient, 'getCatalogCard').mockResolvedValue(
      suggestedProduct,
    );
    vi.spyOn(
      clientModule.apiClient,
      'findCatalogAlternatives',
    ).mockResolvedValue({ cards: [suggestedProduct], next_continuation: null });
    const confirmScan = vi
      .spyOn(clientModule.apiClient, 'confirmScan')
      .mockResolvedValue(undefined);

    renderDetailPage();

    await user.click(
      await screen.findByRole('button', { name: 'Confirm match' }),
    );
    await user.click(screen.getByRole('button', { name: 'Confirm scan' }));

    expect(confirmScan).toHaveBeenCalledTimes(1);
    expect(
      await screen.findByRole('region', { name: 'Scan confirmation' }),
    ).toBeDefined();
    expect(
      await screen.findByText('Import page', {}, { timeout: 4000 }),
    ).toBeDefined();
    expect(getScan).toHaveBeenCalledTimes(3);
  });

  it('keeps review selections when asynchronous confirmation returns to review', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getScan')
      .mockResolvedValueOnce(reviewableDetail())
      .mockResolvedValueOnce(reviewableDetail({ status: 'confirming' }))
      .mockResolvedValue(
        reviewableDetail({ error: 'selected card was not found' }),
      );
    vi.spyOn(clientModule.apiClient, 'getCatalogCard').mockResolvedValue(
      suggestedProduct,
    );
    vi.spyOn(
      clientModule.apiClient,
      'findCatalogAlternatives',
    ).mockResolvedValue({ cards: [suggestedProduct], next_continuation: null });
    vi.spyOn(clientModule.apiClient, 'confirmScan').mockResolvedValue(
      undefined,
    );

    renderDetailPage();

    await user.click(
      await screen.findByRole('button', { name: 'Confirm match' }),
    );
    await user.click(screen.getByRole('button', { name: 'Confirm scan' }));
    await screen.findByRole('region', { name: 'Scan confirmation' });

    expect(
      await screen.findByRole(
        'button',
        { name: 'Match confirmed' },
        { timeout: 4000 },
      ),
    ).toBeDefined();
    expect(screen.getByText('1 of 1 confirmed')).toBeDefined();
    expect(screen.getByText('selected card was not found')).toBeDefined();
  });

  it('keeps polling after a transient error during a reloaded confirmation', async () => {
    vi.useFakeTimers();
    const getScan = vi
      .spyOn(clientModule.apiClient, 'getScan')
      .mockResolvedValueOnce(detail({ status: 'confirming' }))
      .mockRejectedValueOnce(new Error('temporary network error'))
      .mockResolvedValue(
        detail({
          status: 'confirmed',
          import_id: 'import-confirmed',
          rows: [],
        }),
      );

    renderDetailPage();
    await act(async () => {
      await Promise.resolve();
    });
    expect(screen.getByText('Scan confirmation is in progress.')).toBeDefined();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText(/temporary network error/)).toBeDefined();

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getByText('Import page')).toBeDefined();
    expect(getScan).toHaveBeenCalledTimes(3);
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
