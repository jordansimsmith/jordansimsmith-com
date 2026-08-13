import {
  render,
  screen,
  cleanup,
  act,
  waitFor,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ImportDetailPage } from './ImportDetailPage';
import * as clientModule from '../api/client';
import type { ImportDetail, ImportRow } from '../api/client';

function appraisingRows(): ImportRow[] {
  const rows: ImportRow[] = [];
  for (let i = 1; i <= 16; i++) {
    rows.push(importRow(i));
  }
  for (let i = 17; i <= 19; i++) {
    rows.push(
      importRow(i, { decision: 'discard', decision_reason: 'below threshold' }),
    );
  }
  rows.push(
    importRow(20, { decision: 'review', decision_reason: 'non-English' }),
  );
  for (let i = 21; i <= 40; i++) {
    rows.push(
      importRow(i, {
        decision: null,
        market_price: null,
        suggested_price: null,
      }),
    );
  }
  return rows;
}

function importDetail(overrides: Partial<ImportDetail> = {}): ImportDetail {
  return {
    import_id: 'import-2',
    filename: 'manabox-today.csv',
    status: 'appraising',
    row_count: 40,
    appraisal_error: null,
    created_at: 1765420932,
    rows: appraisingRows(),
    ...overrides,
  };
}

function importRow(
  position: number,
  overrides: Partial<ImportRow> = {},
): ImportRow {
  return {
    position,
    name: `Card ${position}`,
    set_code: 'dom',
    set_name: 'Dominaria',
    collector_number: String(position),
    finish: 'normal',
    condition: 'NM',
    scryfall_id: `00000000-0000-4000-8000-${String(position).padStart(12, '0')}`,
    decision: 'keep',
    decision_reason: null,
    market_price: '1.00',
    suggested_price: '0.95',
    ...overrides,
  };
}

function reviewImport(overrides: Partial<ImportDetail> = {}): ImportDetail {
  return importDetail({
    status: 'review',
    row_count: 3,
    rows: [
      importRow(1, {
        name: 'Top Card',
        market_price: '4.55',
        suggested_price: '4.50',
      }),
      importRow(2, {
        name: 'Middle Card',
        decision: 'discard',
        decision_reason: 'market price below NZ$0.25 keep threshold',
        market_price: '0.10',
        suggested_price: null,
      }),
      importRow(3, {
        name: 'Bottom Card',
        decision: 'review',
        decision_reason: 'non-English card',
        market_price: null,
        suggested_price: null,
      }),
    ],
    ...overrides,
  });
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
          rows: [
            ...Array.from({ length: 33 }, (_, i) => importRow(i + 1)),
            ...Array.from({ length: 4 }, (_, i) =>
              importRow(34 + i, {
                decision: 'discard',
                decision_reason: 'below threshold',
              }),
            ),
            ...Array.from({ length: 3 }, (_, i) =>
              importRow(38 + i, {
                decision: 'review',
                decision_reason: 'non-English',
              }),
            ),
          ],
        }),
      );

    renderImportDetailPage();
    await act(async () => {});
    expect(screen.getByText('Appraising 20 of 40')).toBeDefined();
    expect(getImportMock).toHaveBeenCalledTimes(1);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });
    expect(screen.getAllByText('review').length).toBeGreaterThan(0);
    expect(
      screen.getByRole('button', { name: 'Confirm import' }),
    ).toBeDefined();
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

  it('renders review rows in stack order with decisions and prices', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );

    renderImportDetailPage();

    expect(await screen.findByText('Top Card')).toBeDefined();
    const dataRows = screen.getAllByRole('row').slice(1);
    expect(dataRows).toHaveLength(3);
    expect(dataRows[0].textContent).toContain('Top Card');
    expect(dataRows[0].textContent).toContain('$4.55');
    expect(dataRows[0].textContent).toContain('$4.50');
    expect(dataRows[0].textContent).toContain('keep');
    expect(dataRows[1].textContent).toContain('Middle Card');
    expect(dataRows[1].textContent).toContain('discard');
    expect(dataRows[1].textContent).toContain(
      'market price below NZ$0.25 keep threshold',
    );
    expect(dataRows[2].textContent).toContain('Bottom Card');
    expect(dataRows[2].textContent).toContain('review');
    expect(dataRows[2].textContent).toContain('non-English card');
    expect(
      screen.getByRole('button', { name: 'Confirm import' }),
    ).toBeDefined();
  });

  it('moves the selected row with j and k', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    const rowFor = (name: string) => screen.getByText(name).closest('tr');
    expect(rowFor('Top Card')?.getAttribute('data-selected')).toBe('true');

    await user.keyboard('j');
    expect(rowFor('Middle Card')?.getAttribute('data-selected')).toBe('true');

    await user.keyboard('k');
    expect(rowFor('Top Card')?.getAttribute('data-selected')).toBe('true');
  });

  it('confirms the import and shows placement instructions', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );
    vi.spyOn(clientModule.apiClient, 'confirmImport').mockResolvedValue({
      import_id: 'import-2',
      status: 'confirmed',
      unit_count: 87,
      first_sequence_number: 4200,
      last_sequence_number: 4286,
      placement_instructions: [
        {
          block: 'A42',
          from_location: 'A42-0',
          to_location: 'A42-86',
          from_name: 'Llanowar Elves',
          to_name: 'Sol Ring',
          unit_count: 87,
        },
      ],
    });

    renderImportDetailPage();
    await screen.findByText('Top Card');

    await user.click(screen.getByRole('button', { name: 'Confirm import' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/Pull the 1 discard card/)).toBeDefined();
    expect(
      within(dialog).getByText(/Set aside the 1 review card/),
    ).toBeDefined();

    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    await waitFor(() => {
      expect(clientModule.apiClient.confirmImport).toHaveBeenCalledWith(
        'import-2',
      );
    });
    expect(await screen.findByText('Placement instructions')).toBeDefined();
    expect(screen.getByText('A42')).toBeDefined();
    expect(screen.getByText('87 cards')).toBeDefined();
    expect(screen.getByText('Llanowar Elves through Sol Ring')).toBeDefined();
    expect(screen.getByText('A42-0 through A42-86')).toBeDefined();
    expect(screen.getByText('confirmed')).toBeDefined();
    expect(screen.queryByText('Top Card')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Confirm import' })).toBeNull();
  });

  it('surfaces confirm failures and stays on the review table', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );
    vi.spyOn(clientModule.apiClient, 'confirmImport').mockRejectedValue(
      new Error('import is not in review status'),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    await user.click(screen.getByRole('button', { name: 'Confirm import' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

    expect(
      await screen.findByText('import is not in review status'),
    ).toBeDefined();
    expect(screen.getByText('Top Card')).toBeDefined();
    expect(screen.queryByText('Placement instructions')).toBeNull();
  });

  it('allows editing condition via inline select in review status', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );
    vi.spyOn(clientModule.apiClient, 'updateImportRow').mockResolvedValue(
      importRow(1, { name: 'Top Card', condition: 'LP' }),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    const selects = screen.getAllByLabelText(/Condition for row/);
    expect(selects).toHaveLength(3);

    await user.selectOptions(selects[0], 'LP');

    await waitFor(() => {
      expect(clientModule.apiClient.updateImportRow).toHaveBeenCalledWith(
        'import-2',
        1,
        'LP',
      );
    });
  });

  it('deletes a row and updates counts', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );
    vi.spyOn(clientModule.apiClient, 'deleteImportRow').mockResolvedValue(
      undefined,
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    const deleteButtons = screen.getAllByLabelText(/Delete row/);
    expect(deleteButtons).toHaveLength(3);

    await user.click(deleteButtons[0]);

    await waitFor(() => {
      expect(clientModule.apiClient.deleteImportRow).toHaveBeenCalledWith(
        'import-2',
        1,
      );
    });
    expect(screen.queryByText('Top Card')).toBeNull();
    expect(screen.getByText('Keep 0')).toBeDefined();
  });

  it('does not show delete buttons for a confirmed import', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({ status: 'confirmed' }),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    expect(screen.queryByLabelText(/Delete row/)).toBeNull();
  });

  it('does not show condition selects for a confirmed import', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({ status: 'confirmed' }),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    expect(screen.queryByLabelText(/Condition for row/)).toBeNull();
  });

  it('renders a confirmed import read-only without a confirm button', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({ status: 'confirmed' }),
    );

    renderImportDetailPage();

    expect(await screen.findByText('Top Card')).toBeDefined();
    expect(screen.queryByRole('button', { name: 'Confirm import' })).toBeNull();
  });
});
