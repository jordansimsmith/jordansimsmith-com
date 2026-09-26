import { cleanup, render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MantineProvider } from '@mantine/core';
import { Notifications } from '@mantine/notifications';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ScanPage } from './ScanPage';
import * as clientModule from '../api/client';
import * as uploaderModule from '../api/scan-uploader';
import type { ScanDetail, ScanRow, ScanSummary } from '../api/client';

const scanFixtures: ScanSummary[] = [
  {
    scan_id: 'scan-reviewing',
    game: 'mtg',
    status: 'reviewing',
    condition: 'NM',
    finish: 'normal',
    row_count: 100,
    error: 'one image needs a manual printing choice',
    import_id: null,
    created_at: 1765420932,
  },
  {
    scan_id: 'scan-confirmed',
    game: 'mtg',
    status: 'confirmed',
    condition: 'LP',
    finish: 'foil',
    row_count: 48,
    error: null,
    import_id: 'import-7',
    created_at: 1764816132,
  },
];

function scanRow(overrides: Partial<ScanRow> = {}): ScanRow {
  return {
    scan_position: 1,
    filename: '001.jpg',
    size_bytes: 4,
    uploaded: false,
    upload_url: 'fake://scan-created/000001',
    upload_headers: { 'Content-Type': 'image/jpeg' },
    status: null,
    needs_review: false,
    suggestions: [],
    source_url: null,
    error: null,
    ...overrides,
  };
}

function scanDetail(overrides: Partial<ScanDetail> = {}): ScanDetail {
  return {
    ...scanFixtures[0],
    scan_id: 'scan-created',
    status: 'uploading',
    row_count: 1,
    error: null,
    import_id: null,
    rows: [scanRow()],
    ...overrides,
  };
}

function renderScanPage() {
  return render(
    <MantineProvider>
      <Notifications />
      <MemoryRouter initialEntries={['/scans']}>
        <Routes>
          <Route path="/scans" element={<ScanPage />} />
          <Route path="/scans/:scanId" element={<div>Scan detail route</div>} />
        </Routes>
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

  it('renders the compact intake surface without preview or confirmation controls', async () => {
    renderScanPage();

    const jobs = screen.getByRole('region', { name: 'Scan jobs' });

    expect(within(jobs).getByRole('textbox', { name: 'Game' })).toBeDefined();
    expect(
      within(jobs).getByRole('textbox', { name: 'Condition' }),
    ).toBeDefined();
    const finishInput = within(jobs).getByRole('textbox', { name: 'Finish' });
    expect(finishInput.getAttribute('placeholder')).toBe('Select game first');
    expect(finishInput.hasAttribute('disabled')).toBe(true);
    expect(within(jobs).getByLabelText(/Scanner JPEGs/)).toBeDefined();
    expect(
      (
        within(jobs).getByRole('button', {
          name: 'Create scan',
        }) as HTMLButtonElement
      ).disabled,
    ).toBe(true);
    expect(await within(jobs).findByText('review')).toBeDefined();
    const scanRow = within(jobs)
      .getByText('100')
      .closest('tr') as HTMLTableRowElement;
    expect(within(scanRow).getByText('Magic: The Gathering')).toBeDefined();
    expect(within(jobs).getByText('100')).toBeDefined();
    expect(within(jobs).getByText('Foil')).toBeDefined();
    expect(
      screen.queryByRole('region', { name: 'Selected scan files' }),
    ).toBeNull();
    expect(screen.queryByRole('checkbox')).toBeNull();
    expect(screen.queryByRole('button', { name: 'Identify scan' })).toBeNull();
  });

  it('refreshes the table then uploads, verifies, identifies, and navigates', async () => {
    const created = scanDetail();
    const verified = scanDetail({
      rows: [
        scanRow({ uploaded: true, upload_url: null, upload_headers: null }),
      ],
    });
    const refreshedScan: ScanSummary = {
      ...scanFixtures[0],
      scan_id: 'scan-created',
      status: 'uploading',
      row_count: 1,
      error: null,
    };
    const findScans = vi
      .spyOn(clientModule.apiClient, 'findScans')
      .mockResolvedValueOnce({ scans: scanFixtures, next_continuation: null })
      .mockResolvedValueOnce({
        scans: [refreshedScan],
        next_continuation: null,
      });
    const createScan = vi
      .spyOn(clientModule.apiClient, 'createScan')
      .mockResolvedValue(created);
    const uploadBatch = vi
      .spyOn(uploaderModule.scanUploader, 'uploadBatch')
      .mockResolvedValue(undefined);
    const getScan = vi
      .spyOn(clientModule.apiClient, 'getScan')
      .mockResolvedValue(verified);
    const identifyScan = vi
      .spyOn(clientModule.apiClient, 'identifyScan')
      .mockResolvedValue({ scan_id: 'scan-created', status: 'identifying' });
    const user = userEvent.setup();
    const { container } = renderScanPage();
    const fileInput = container.querySelector('input[type="file"]');

    await user.click(screen.getByRole('textbox', { name: 'Game' }));
    await user.keyboard('{ArrowDown}{Enter}');

    await user.upload(
      fileInput as HTMLInputElement,
      new File(['jpeg'], '001.jpg', { type: 'image/jpeg' }),
    );
    await user.click(screen.getByRole('button', { name: 'Create scan' }));

    expect(createScan).toHaveBeenCalledWith({
      game: 'mtg',
      condition: 'NM',
      finish: 'normal',
      files: [{ filename: '001.jpg', size_bytes: 4 }],
    });
    expect(findScans).toHaveBeenCalledTimes(2);
    expect(uploadBatch).toHaveBeenCalledWith('scan-created', [
      { slot: created.rows[0], file: expect.any(File) },
    ]);
    expect(getScan).toHaveBeenCalledWith('scan-created');
    expect(identifyScan).toHaveBeenCalledWith('scan-created');
    expect(findScans.mock.invocationCallOrder[1]).toBeLessThan(
      uploadBatch.mock.invocationCallOrder[0],
    );
    expect(uploadBatch.mock.invocationCallOrder[0]).toBeLessThan(
      getScan.mock.invocationCallOrder[0],
    );
    expect(getScan.mock.invocationCallOrder[0]).toBeLessThan(
      identifyScan.mock.invocationCallOrder[0],
    );
    expect(await screen.findByText('Scan detail route')).toBeDefined();
  });

  it('shows validation errors before creating a scan', async () => {
    const createScan = vi.spyOn(clientModule.apiClient, 'createScan');
    const user = userEvent.setup();
    const { container } = renderScanPage();
    const fileInput = container.querySelector('input[type="file"]');

    await user.upload(fileInput as HTMLInputElement, [
      new File([''], 'same.jpg', { type: 'image/jpeg' }),
      new File(['jpeg'], 'same.jpg', { type: 'image/jpeg' }),
      new File(['png'], 'other.jpg', { type: 'image/png' }),
    ]);

    const validationAlert = screen.getByRole('alert');
    expect(validationAlert.textContent).toContain('File names must be unique.');
    expect(validationAlert.textContent).toContain('Files must not be empty.');
    expect(validationAlert.textContent).toContain(
      'Only .jpg and .jpeg files are supported.',
    );
    expect(
      (screen.getByRole('button', { name: 'Create scan' }) as HTMLButtonElement)
        .disabled,
    ).toBe(true);
    expect(createScan).not.toHaveBeenCalled();
  });

  it('retains the files and leaves a failed job without retry controls', async () => {
    const created = scanDetail();
    const failedSummary: ScanSummary = {
      ...scanFixtures[0],
      scan_id: 'scan-created',
      status: 'uploading',
      row_count: 1,
      error: null,
    };
    vi.spyOn(clientModule.apiClient, 'createScan').mockResolvedValue(created);
    vi.spyOn(clientModule.apiClient, 'findScans')
      .mockResolvedValueOnce({ scans: scanFixtures, next_continuation: null })
      .mockResolvedValueOnce({
        scans: [failedSummary],
        next_continuation: null,
      });
    vi.spyOn(uploaderModule.scanUploader, 'uploadBatch').mockRejectedValue(
      new Error('network interrupted'),
    );
    const user = userEvent.setup();
    const { container } = renderScanPage();
    const fileInput = container.querySelector(
      'input[type="file"]',
    ) as HTMLInputElement;
    await user.upload(
      fileInput,
      new File(['jpeg'], '001.jpg', { type: 'image/jpeg' }),
    );
    await user.click(screen.getByRole('textbox', { name: 'Game' }));
    await user.keyboard('{ArrowDown}{Enter}');
    await user.click(screen.getByRole('button', { name: 'Create scan' }));

    expect(await screen.findAllByText('network interrupted')).not.toHaveLength(
      0,
    );
    expect(
      within(screen.getByRole('region', { name: 'Scan jobs' })).getByText(
        'uploading',
      ),
    ).toBeDefined();
    expect(fileInput.files).toHaveLength(1);
    expect(screen.queryByRole('button', { name: /retry|resume/i })).toBeNull();
  });

  it('opens a scan detail route when a table row is selected', async () => {
    const user = userEvent.setup();
    renderScanPage();

    await user.click(await screen.findByText('review'));

    expect(await screen.findByText('Scan detail route')).toBeDefined();
  });
});
