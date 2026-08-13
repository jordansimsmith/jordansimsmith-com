import {
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
import * as clientModule from '../api/client';
import type { ImportSummary } from '../api/client';

const importFixtures: ImportSummary[] = [
  {
    import_id: 'import-2',
    filename: 'manabox-today.csv',
    status: 'appraising',
    row_count: 40,
    appraisal_error: null,
    created_at: 1765420932,
  },
  {
    import_id: 'import-1',
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

function renderImportsPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/imports']}>
        <Routes>
          <Route path="/imports" element={<ImportsPage />} />
          <Route path="/imports/:importId" element={<ImportDetailStub />} />
        </Routes>
      </MemoryRouter>
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
    });

    renderImportsPage();

    expect(await screen.findByText('No imports yet.')).toBeDefined();
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
      'bulk.csv',
      VALID_CSV,
    );
  });

  it('rejects an invalid csv client-side without creating an import', async () => {
    const user = userEvent.setup();
    renderImportsPage();
    await screen.findByText('manabox-today.csv');

    const file = new File(['Name,Quantity\nOpt,1'], 'bad.csv', {
      type: 'text/csv',
    });
    await user.upload(getFileInput(), file);
    await user.click(screen.getByRole('button', { name: 'Upload' }));

    expect(await screen.findByText('Upload failed')).toBeDefined();
    expect(
      screen.getByText(/CSV is missing columns/, { exact: false }),
    ).toBeDefined();
    expect(clientModule.apiClient.createImport).not.toHaveBeenCalled();
  });

  it('opens the selected import with Enter', async () => {
    const user = userEvent.setup();
    renderImportsPage();
    await screen.findByText('manabox-today.csv');

    await user.keyboard('j');
    await user.keyboard('{Enter}');

    await waitFor(() => {
      expect(screen.getByText('Import detail import-1')).toBeDefined();
    });
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
});
