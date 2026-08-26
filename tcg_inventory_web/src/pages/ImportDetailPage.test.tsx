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
import { encodeListingPhoto } from '../domain/encode-listing-photo';

vi.mock('../domain/encode-listing-photo', () => ({
  encodeListingPhoto: vi.fn(),
}));

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
    photos: [],
    needs_photos: false,
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
    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
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

  it('places finish after name and bolds non-normal finishes', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({
        rows: [
          importRow(1, { name: 'Normal Card', finish: 'normal' }),
          importRow(2, {
            name: 'Foil Card',
            finish: 'foil',
            decision: 'keep',
          }),
          importRow(3, {
            name: 'Etched Card',
            finish: 'etched',
            decision: 'keep',
          }),
        ],
      }),
    );

    renderImportDetailPage();
    await screen.findByText('Normal Card');

    const headers = screen
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(headers.indexOf('Finish')).toBe(headers.indexOf('Name') + 1);

    const cellAfterName = (name: string) => {
      const row = screen.getByText(name).closest('tr') as HTMLElement;
      return within(row).getAllByRole('cell')[2];
    };

    const normalCell = cellAfterName('Normal Card');
    expect(normalCell.textContent).toBe('normal');
    expect(normalCell.style.fontWeight).toBe('');

    const foilCell = cellAfterName('Foil Card');
    expect(foilCell.textContent).toBe('foil');
    expect(foilCell.style.fontWeight).toBe('700');
    expect(foilCell.style.textTransform).toBe('capitalize');

    const etchedCell = cellAfterName('Etched Card');
    expect(etchedCell.textContent).toBe('etched');
    expect(etchedCell.style.fontWeight).toBe('700');
    expect(etchedCell.style.textTransform).toBe('capitalize');
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
      total_suggested_price: '342.50',
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
    expect(screen.getByText('Total suggested value $342.50')).toBeDefined();
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

  it('deletes an import via the confirmation dialog and navigates away', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );
    vi.spyOn(clientModule.apiClient, 'deleteImport').mockResolvedValue(
      undefined,
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    await user.click(screen.getByRole('button', { name: 'Delete import' }));
    const dialog = await screen.findByRole('dialog');
    expect(within(dialog).getByText(/manabox-today.csv/)).toBeDefined();
    expect(within(dialog).getByText(/3 rows/)).toBeDefined();

    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

    await waitFor(() => {
      expect(clientModule.apiClient.deleteImport).toHaveBeenCalledWith(
        'import-2',
      );
    });
    await waitFor(() => {
      expect(screen.getByText('Imports list')).toBeDefined();
    });
  });

  it('surfaces delete failures and stays on the detail page', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );
    vi.spyOn(clientModule.apiClient, 'deleteImport').mockRejectedValue(
      new Error('import is not in a deletable status'),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    await user.click(screen.getByRole('button', { name: 'Delete import' }));
    const dialog = await screen.findByRole('dialog');
    await user.click(within(dialog).getByRole('button', { name: 'Delete' }));

    expect(
      await screen.findByText('import is not in a deletable status'),
    ).toBeDefined();
    expect(screen.getByText('Top Card')).toBeDefined();
  });

  it('does not show delete button for a confirmed import', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({ status: 'confirmed' }),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    expect(screen.queryByRole('button', { name: 'Delete import' })).toBeNull();
  });

  it('does not show delete button while appraising', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      importDetail(),
    );

    renderImportDetailPage();
    await screen.findByText('manabox-today.csv');

    expect(screen.queryByRole('button', { name: 'Delete import' })).toBeNull();
  });

  it('shows a needs photos badge only when the server flags the row', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({
        rows: [
          importRow(1, {
            name: 'Flagged Card',
            suggested_price: '60.00',
            needs_photos: true,
          }),
          importRow(2, {
            name: 'High Value Unflagged',
            suggested_price: '60.00',
            needs_photos: false,
          }),
          importRow(3, {
            name: 'Middle Card',
            decision: 'discard',
            decision_reason: 'market price below NZ$0.25 keep threshold',
            market_price: '0.10',
            suggested_price: null,
          }),
        ],
      }),
    );

    renderImportDetailPage();

    expect(await screen.findByText('Flagged Card')).toBeDefined();
    const flaggedRow = screen.getByText('Flagged Card').closest('tr');
    expect(flaggedRow).not.toBeNull();
    expect(
      within(flaggedRow as HTMLElement).getByText('Needs photos'),
    ).toBeDefined();
    expect(
      within(flaggedRow as HTMLElement).getByLabelText('Add photo to row 1'),
    ).toBeDefined();
    const unflaggedRow = screen.getByText('High Value Unflagged').closest('tr');
    expect(unflaggedRow).not.toBeNull();
    expect(
      within(unflaggedRow as HTMLElement).queryByText('Needs photos'),
    ).toBeNull();
    expect(
      within(unflaggedRow as HTMLElement).getByLabelText('Add photo to row 2'),
    ).toBeDefined();
  });

  it('shows a photo strip on keep rows only', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport(),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    expect(screen.getByLabelText('Add photo to row 1')).toBeDefined();
    expect(screen.queryByLabelText('Add photo to row 2')).toBeNull();
    expect(screen.queryByLabelText('Add photo to row 3')).toBeNull();
    expect(screen.queryByRole('button', { name: /primary/i })).toBeNull();
  });

  it('keeps the add photo control leftmost when photos already exist', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({
        rows: [
          importRow(1, {
            name: 'Top Card',
            photos: [
              { photo_id: 'photo-1', url: 'https://example.com/p1.jpg' },
              { photo_id: 'photo-2', url: 'https://example.com/p2.jpg' },
            ],
          }),
        ],
      }),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    const row = screen.getByText('Top Card').closest('tr') as HTMLElement;
    const add = within(row).getByLabelText('Add photo to row 1');
    const firstThumb = within(row).getByAltText('Listing photo photo-1');
    expect(
      add.compareDocumentPosition(firstThumb) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it('adds a photo through the canvas util and refetches the import', async () => {
    const user = userEvent.setup();
    const encodedJpeg = new Blob(['encoded'], { type: 'image/jpeg' });
    vi.mocked(encodeListingPhoto).mockResolvedValue(encodedJpeg);
    const flagged = reviewImport({
      rows: [
        importRow(1, {
          name: 'Top Card',
          suggested_price: '60.00',
          needs_photos: true,
        }),
        importRow(2, {
          name: 'Middle Card',
          decision: 'discard',
          decision_reason: 'market price below NZ$0.25 keep threshold',
          market_price: '0.10',
          suggested_price: null,
        }),
      ],
    });
    const withPhoto = reviewImport({
      rows: [
        importRow(1, {
          name: 'Top Card',
          suggested_price: '60.00',
          needs_photos: false,
          photos: [{ photo_id: 'photo-1', url: 'https://example.com/p1.jpg' }],
        }),
        importRow(2, {
          name: 'Middle Card',
          decision: 'discard',
          decision_reason: 'market price below NZ$0.25 keep threshold',
          market_price: '0.10',
          suggested_price: null,
        }),
      ],
    });
    const getImportMock = vi
      .spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValueOnce(flagged)
      .mockResolvedValue(withPhoto);
    vi.spyOn(clientModule.apiClient, 'addRowPhoto').mockResolvedValue(
      undefined,
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');
    expect(screen.getByText('Needs photos')).toBeDefined();

    const file = new File(['src'], 'card.jpg', { type: 'image/jpeg' });
    await user.upload(screen.getByLabelText('Add photo to row 1'), file);

    await waitFor(() => {
      expect(encodeListingPhoto).toHaveBeenCalledWith(file);
      expect(clientModule.apiClient.addRowPhoto).toHaveBeenCalledWith(
        'import-2',
        1,
        encodedJpeg,
      );
    });
    await waitFor(() => {
      expect(getImportMock).toHaveBeenCalledTimes(2);
    });
    expect(screen.getByAltText('Listing photo photo-1')).toBeDefined();
    expect(screen.queryByText('Needs photos')).toBeNull();
  });

  it('removes a photo and refetches the import', async () => {
    const user = userEvent.setup();
    const withPhoto = reviewImport({
      rows: [
        importRow(1, {
          name: 'Top Card',
          suggested_price: '60.00',
          needs_photos: false,
          photos: [{ photo_id: 'photo-1', url: 'https://example.com/p1.jpg' }],
        }),
      ],
    });
    const withoutPhoto = reviewImport({
      rows: [
        importRow(1, {
          name: 'Top Card',
          suggested_price: '60.00',
          needs_photos: true,
        }),
      ],
    });
    vi.spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValueOnce(withPhoto)
      .mockResolvedValue(withoutPhoto);
    vi.spyOn(clientModule.apiClient, 'deleteRowPhoto').mockResolvedValue(
      undefined,
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    await user.click(
      screen.getByRole('button', { name: 'Remove photo photo-1' }),
    );

    await waitFor(() => {
      expect(clientModule.apiClient.deleteRowPhoto).toHaveBeenCalledWith(
        'import-2',
        1,
        'photo-1',
      );
    });
    await waitFor(() => {
      expect(screen.getByText('Needs photos')).toBeDefined();
    });
    expect(screen.queryByAltText('Listing photo photo-1')).toBeNull();
  });

  it('pluralises the confirm gate reason', async () => {
    vi.spyOn(clientModule.apiClient, 'getImport').mockResolvedValue(
      reviewImport({
        rows: [
          importRow(1, { name: 'Top Card', needs_photos: true }),
          importRow(2, { name: 'Second Card', needs_photos: true }),
        ],
      }),
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    expect(screen.getByText('2 rows need photos before confirm')).toBeDefined();
  });

  it('disables confirm while any row needs photos and enables after they are photographed', async () => {
    const user = userEvent.setup();
    const encodedJpeg = new Blob(['encoded'], { type: 'image/jpeg' });
    vi.mocked(encodeListingPhoto).mockResolvedValue(encodedJpeg);
    const flagged = reviewImport({
      rows: [
        importRow(1, {
          name: 'Top Card',
          suggested_price: '60.00',
          needs_photos: true,
        }),
      ],
    });
    const photographed = reviewImport({
      rows: [
        importRow(1, {
          name: 'Top Card',
          suggested_price: '60.00',
          needs_photos: false,
          photos: [{ photo_id: 'photo-1', url: 'https://example.com/p1.jpg' }],
        }),
      ],
    });
    vi.spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValueOnce(flagged)
      .mockResolvedValueOnce(photographed)
      .mockResolvedValue(flagged);
    vi.spyOn(clientModule.apiClient, 'addRowPhoto').mockResolvedValue(
      undefined,
    );
    vi.spyOn(clientModule.apiClient, 'deleteRowPhoto').mockResolvedValue(
      undefined,
    );

    renderImportDetailPage();
    await screen.findByText('Top Card');

    expect(
      screen.getByRole('button', { name: 'Confirm import' }),
    ).toHaveProperty('disabled', true);
    expect(screen.getByText('1 row needs photos before confirm')).toBeDefined();

    const file = new File(['src'], 'card.jpg', { type: 'image/jpeg' });
    await user.upload(screen.getByLabelText('Add photo to row 1'), file);

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: 'Confirm import' }),
      ).toHaveProperty('disabled', false);
    });
    expect(screen.queryByText('1 row needs photos before confirm')).toBeNull();

    await user.click(
      screen.getByRole('button', { name: 'Remove photo photo-1' }),
    );

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: 'Confirm import' }),
      ).toHaveProperty('disabled', true);
    });
    expect(screen.getByText('1 row needs photos before confirm')).toBeDefined();
  });

  it('refetches the import on window refocus while in review', async () => {
    const getImportMock = vi
      .spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValue(reviewImport());

    renderImportDetailPage();
    await screen.findByText('Top Card');
    expect(getImportMock).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    await waitFor(() => {
      expect(getImportMock).toHaveBeenCalledTimes(2);
    });
  });

  it('does not refetch on refocus while appraising', async () => {
    const getImportMock = vi
      .spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValue(importDetail());

    renderImportDetailPage();
    await screen.findByText('manabox-today.csv');
    expect(getImportMock).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(getImportMock).toHaveBeenCalledTimes(1);
  });

  it('does not refetch on refocus when the import is confirmed', async () => {
    const getImportMock = vi
      .spyOn(clientModule.apiClient, 'getImport')
      .mockResolvedValue(reviewImport({ status: 'confirmed' }));

    renderImportDetailPage();
    await screen.findByText('Top Card');
    expect(getImportMock).toHaveBeenCalledTimes(1);

    Object.defineProperty(document, 'visibilityState', {
      configurable: true,
      get: () => 'visible',
    });
    await act(async () => {
      document.dispatchEvent(new Event('visibilitychange'));
    });

    expect(getImportMock).toHaveBeenCalledTimes(1);
  });
});
