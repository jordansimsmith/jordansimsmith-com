import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScanPage } from './ScanPage';
import * as clientModule from '../api/client';
import type { ScanDetail, ScanSummary } from '../api/client';

const scanFixtures: ScanSummary[] = [
  {
    scan_id: 'scan-new',
    status: 'reviewing',
    condition: 'NM',
    finish: 'normal',
    row_count: 100,
    processed_count: 100,
    error: 'one image needs a manual printing choice',
    import_id: null,
    created_at: 1765420932,
  },
  {
    scan_id: 'scan-old',
    status: 'confirmed',
    condition: 'LP',
    finish: 'foil',
    row_count: 48,
    processed_count: 48,
    error: null,
    import_id: 'import-7',
    created_at: 1764816132,
  },
];

function renderScanPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/scan']}>
        <ScanPage />
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('ScanPage', () => {
  beforeEach(() => {
    vi.spyOn(clientModule.apiClient, 'findScans').mockResolvedValue({
      scans: scanFixtures,
      next_continuation: null,
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
    cleanup();
  });

  it('renders the new scan surface above resumable jobs', async () => {
    renderScanPage();

    const jobs = screen.getByRole('region', { name: 'Scan jobs' });

    expect(within(jobs).getByLabelText('Condition')).toBeDefined();
    expect(within(jobs).getByLabelText('Finish')).toBeDefined();
    expect(within(jobs).getByLabelText('Scanner JPEGs')).toBeDefined();
    expect(
      within(jobs).getByRole('button', { name: 'Create scan' }),
    ).toBeDefined();
    expect(await within(jobs).findByText('reviewing')).toBeDefined();
    expect(within(jobs).getByText('100 / 100')).toBeDefined();
    expect(within(jobs).getByText('Foil')).toBeDefined();
    expect(
      within(jobs).getByRole('columnheader', { name: 'Created' }),
    ).toBeDefined();
    expect(
      within(jobs).queryByRole('columnheader', { name: 'Import' }),
    ).toBeNull();
    expect(
      screen.queryByText('one image needs a manual printing choice'),
    ).toBeNull();
  });

  it('adds the created scan to the top of the table', async () => {
    const created: ScanDetail = {
      ...scanFixtures[0],
      scan_id: 'scan-created',
      status: 'uploading',
      row_count: 1,
      processed_count: 0,
      error: null,
      import_id: null,
      rows: [],
    };
    const createScan = vi
      .spyOn(clientModule.apiClient, 'createScan')
      .mockResolvedValue(created);
    const user = userEvent.setup();
    const { container } = renderScanPage();
    const fileInput = container.querySelector('input[type="file"]');
    expect(fileInput).not.toBeNull();
    const file = new File(['jpeg'], '001.jpg', { type: 'image/jpeg' });

    await user.upload(fileInput as HTMLInputElement, file);
    await user.click(screen.getByRole('button', { name: 'Create scan' }));

    expect(createScan).toHaveBeenCalledWith({
      condition: 'NM',
      finish: 'normal',
      files: [{ filename: '001.jpg', size_bytes: 4 }],
    });
    expect(await screen.findByText('0 / 1')).toBeDefined();
  });

  it('shows an empty state', async () => {
    vi.spyOn(clientModule.apiClient, 'findScans').mockResolvedValue({
      scans: [],
      next_continuation: null,
    });

    renderScanPage();

    expect(await screen.findByText('No scans yet.')).toBeDefined();
  });

  it('shows an error state when the list fails', async () => {
    vi.spyOn(clientModule.apiClient, 'findScans').mockRejectedValue(
      new Error('scan service unavailable'),
    );

    renderScanPage();

    expect(await screen.findByText('Scans could not be loaded')).toBeDefined();
    expect(screen.getAllByText('scan service unavailable')).toHaveLength(2);
  });

  it('appends continuation results', async () => {
    const olderScan: ScanSummary = {
      ...scanFixtures[1],
      scan_id: 'scan-oldest',
      condition: 'DMG',
    };
    const findScans = vi.spyOn(clientModule.apiClient, 'findScans');
    findScans
      .mockResolvedValueOnce({
        scans: scanFixtures,
        next_continuation: 'page-2',
      })
      .mockResolvedValueOnce({ scans: [olderScan], next_continuation: null });

    const user = userEvent.setup();
    renderScanPage();
    await screen.findByText('100 / 100');

    await user.click(screen.getByRole('button', { name: 'Load more' }));

    expect(
      within(screen.getByRole('region', { name: 'Scan jobs' })).getByText(
        'DMG',
      ),
    ).toBeDefined();
    expect(findScans).toHaveBeenLastCalledWith({ continuation: 'page-2' });
    expect(screen.queryByRole('button', { name: 'Load more' })).toBeNull();
  });

  it('keeps the loading collection structure while the request is pending', () => {
    vi.spyOn(clientModule.apiClient, 'findScans').mockReturnValue(
      new Promise(() => {}),
    );

    renderScanPage();

    expect(screen.getByLabelText('Loading collection')).toBeDefined();
    expect(
      within(screen.getByRole('region', { name: 'Scan jobs' })).getByLabelText(
        'Condition',
      ),
    ).toBeDefined();
  });
});
