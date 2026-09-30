import {
  fireEvent,
  render,
  screen,
  waitFor,
  cleanup,
  within,
} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Routes, Route, useParams } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ImportsPage } from './ImportsPage';
import { GamesProvider } from '../GamesProvider';
import * as clientModule from '../api/client';
import type { Game, ImportSummary } from '../api/client';

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

const importFixtures: ImportSummary[] = [
  {
    import_id: 'import-2',
    game: 'mtg',
    filename: 'manabox-today.csv',
    status: 'appraising',
    row_count: 40,
    appraisal_error: null,
    created_at: 1765420932,
  },
  {
    import_id: 'import-1',
    game: 'mtg',
    filename: 'manabox-last-week.csv',
    status: 'confirmed',
    row_count: 24,
    appraisal_error: null,
    created_at: 1764816132,
  },
];

const VALID_CSV = [
  'Name,Set code,Set name,Collector number,Foil,Rarity,Quantity,Scryfall ID,Misprint,Altered,Condition,Language',
  'Opt,dom,Dominaria,60,normal,common,1,25f2e4d0-effd-4e83-b7aa-1a0d8f120951,false,false,near_mint,en',
].join('\n');

function ImportDetailStub() {
  const { importId } = useParams<{ importId: string }>();
  return <div>Import detail {importId}</div>;
}

function renderImportsPage(games?: Game[]) {
  return render(
    <MantineProvider>
      <Notifications />
      <GamesProvider initialGames={games ?? REGISTERED_GAMES}>
        <MemoryRouter initialEntries={['/imports']}>
          <Routes>
            <Route path="/imports" element={<ImportsPage />} />
            <Route path="/imports/:importId" element={<ImportDetailStub />} />
          </Routes>
        </MemoryRouter>
      </GamesProvider>
    </MantineProvider>,
  );
}

function getFileInput(): HTMLInputElement {
  const input = document.querySelector('input[type="file"]');
  expect(input).not.toBeNull();
  return input as HTMLInputElement;
}

describe('ImportsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.spyOn(clientModule.apiClient, 'findImports').mockResolvedValue({
      imports: importFixtures,
      next_continuation: null,
    });
    vi.spyOn(clientModule.apiClient, 'createImport').mockResolvedValue({
      ...importFixtures[0],
      import_id: 'import-3',
      filename: 'bulk.csv',
      row_count: 1,
    });
  });

  afterEach(() => {
    cleanup();
  });

  it('renders import rows with status and row count', async () => {
    renderImportsPage();

    const row = (await screen.findByText('manabox-today.csv')).closest(
      'tr',
    ) as HTMLTableRowElement;

    expect(within(row).getByText('Magic: The Gathering')).toBeDefined();
    expect(within(row).getByText('appraising')).toBeDefined();
    expect(within(row).getByText('40')).toBeDefined();
    const confirmedRow = screen
      .getByText('manabox-last-week.csv')
      .closest('tr') as HTMLTableRowElement;
    expect(within(confirmedRow).getByText('confirmed')).toBeDefined();
    expect(within(confirmedRow).getByText('24')).toBeDefined();
  });

  it('shows an empty state when there are no imports', async () => {
    vi.spyOn(clientModule.apiClient, 'findImports').mockResolvedValue({
      imports: [],
      next_continuation: null,
    });

    renderImportsPage();

    expect(await screen.findByText('No imports yet.')).toBeDefined();
  });

  it('defaults to an import-enabled game and keeps disabled games in history', async () => {
    const games: Game[] = [
      {
        id: 'mtg',
        display_name: 'Legacy Cards',
        scanning_enabled: true,
        csv_import_enabled: false,
        scan_review_image_regions: [],
        finishes: [{ id: 'normal', display_name: 'Base' }],
      },
      {
        id: 'other-game',
        display_name: 'New Cards',
        scanning_enabled: false,
        csv_import_enabled: true,
        scan_review_image_regions: [],
        finishes: [{ id: 'plain', display_name: 'Plain' }],
      },
    ];
    const user = userEvent.setup();
    renderImportsPage(games);

    expect(await screen.findByText('manabox-today.csv')).toBeDefined();
    const jobs = screen.getByRole('region', { name: 'Imports' });
    const gameInput = within(jobs).getByRole('textbox', { name: 'Game' });
    const filePicker = within(jobs).getByRole('button', { name: 'CSV export' });
    expect(gameInput).toHaveProperty('value', 'New Cards');
    expect(within(jobs).getAllByText('Legacy Cards').length).toBeGreaterThan(0);
    expect((filePicker as HTMLButtonElement).disabled).toBe(false);

    await user.click(gameInput);
    fireEvent.click(
      screen.getByRole('option', { name: 'Legacy Cards', hidden: true }),
    );

    expect(gameInput).toHaveProperty('value', 'Legacy Cards');
    expect((filePicker as HTMLButtonElement).disabled).toBe(true);
    expect(
      within(jobs).getByText('CSV imports are available for New Cards.'),
    ).toBeDefined();
    expect(screen.getByText('manabox-today.csv')).toBeDefined();
  });

  it('uploads a valid csv and navigates to the import', async () => {
    const user = userEvent.setup();
    renderImportsPage();
    await screen.findByText('manabox-today.csv');

    const file = new File([VALID_CSV], 'bulk.csv', { type: 'text/csv' });
    await user.upload(getFileInput(), file);
    await user.click(screen.getByRole('button', { name: 'Upload' }));

    await waitFor(() => {
      expect(screen.getByText('Import detail import-3')).toBeDefined();
    });
    expect(clientModule.apiClient.createImport).toHaveBeenCalledWith(
      'mtg',
      'bulk.csv',
      VALID_CSV,
    );
  });

  it('shows API CSV parsing errors without parsing the upload in the browser', async () => {
    const user = userEvent.setup();
    vi.spyOn(clientModule.apiClient, 'createImport').mockRejectedValue(
      new Error('CSV is missing columns: Name, Quantity'),
    );
    renderImportsPage();
    await screen.findByText('manabox-today.csv');

    const invalidCsv = 'Name,Quantity\nOpt,1';
    const file = new File([invalidCsv], 'bad.csv', {
      type: 'text/csv',
    });
    await user.upload(getFileInput(), file);
    await user.click(screen.getByRole('button', { name: 'Upload' }));

    expect(await screen.findByText('Upload failed')).toBeDefined();
    expect(
      screen.getByText('CSV is missing columns: Name, Quantity'),
    ).toBeDefined();
    expect(clientModule.apiClient.createImport).toHaveBeenCalledWith(
      'mtg',
      'bad.csv',
      invalidCsv,
    );
  });

  it('navigates when a row is clicked', async () => {
    const user = userEvent.setup();
    renderImportsPage();
    await screen.findByText('manabox-today.csv');

    await user.click(screen.getByText('manabox-today.csv'));

    await waitFor(() => {
      expect(screen.getByText('Import detail import-2')).toBeDefined();
    });
  });

  it('appends the next page when load more is clicked', async () => {
    const nextPage: ImportSummary = {
      import_id: 'import-0',
      game: 'mtg',
      filename: 'manabox-older.csv',
      status: 'confirmed',
      row_count: 12,
      appraisal_error: null,
      created_at: 1764211332,
    };
    vi.spyOn(clientModule.apiClient, 'findImports').mockImplementation(
      async (params) => {
        if (params?.continuation === 'page-2') {
          return { imports: [nextPage], next_continuation: null };
        }
        return { imports: importFixtures, next_continuation: 'page-2' };
      },
    );
    const user = userEvent.setup();
    renderImportsPage();
    await screen.findByText('manabox-today.csv');
    expect(screen.queryByText('manabox-older.csv')).toBeNull();

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    expect(await screen.findByText('manabox-older.csv')).toBeDefined();
    expect(clientModule.apiClient.findImports).toHaveBeenLastCalledWith({
      continuation: 'page-2',
    });
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });
});
