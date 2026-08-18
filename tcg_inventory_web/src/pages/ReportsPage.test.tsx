import { render, screen, cleanup, act } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ReportsPage } from './ReportsPage';
import * as clientModule from '../api/client';
import type { ReportResponse } from '../api/client';

const baseReport: ReportResponse = {
  generated_at: Math.floor(Date.now() / 1000) - 3600,
  stale: false,
  generation: {
    status: 'succeeded',
    error: null,
    started_at: Math.floor(Date.now() / 1000) - 3700,
    finished_at: Math.floor(Date.now() / 1000) - 3600,
  },
  report: {},
};

function renderReportsPage() {
  return render(
    <MantineProvider>
      <MemoryRouter initialEntries={['/reports']}>
        <Routes>
          <Route path="/reports" element={<ReportsPage />} />
        </Routes>
      </MemoryRouter>
    </MantineProvider>,
  );
}

describe('ReportsPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('shouldRenderDataAsOfStamp', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText(/Data as of/)).toBeDefined();
  });

  it('shouldShowSkeletonsOnFirstVisit', async () => {
    const getReportMock = vi
      .spyOn(clientModule.apiClient, 'getReport')
      .mockRejectedValue(new Error('Not Found'));
    const createReportMock = vi
      .spyOn(clientModule.apiClient, 'createReport')
      .mockResolvedValue(undefined);

    renderReportsPage();
    await act(async () => {});

    expect(createReportMock).toHaveBeenCalled();
    expect(screen.queryByText(/Data as of/)).toBeNull();
    expect(screen.queryByText('No report data yet.')).toBeNull();

    getReportMock.mockResolvedValue({ ...baseReport });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByText(/Data as of/)).toBeDefined();
  });

  it('shouldTriggerRegenerationWhenStale', async () => {
    const getReportMock = vi
      .spyOn(clientModule.apiClient, 'getReport')
      .mockResolvedValue({ ...baseReport, stale: true });
    const createReportMock = vi
      .spyOn(clientModule.apiClient, 'createReport')
      .mockResolvedValue(undefined);

    renderReportsPage();
    await act(async () => {});

    expect(createReportMock).toHaveBeenCalled();
    expect(screen.getByText(/Data as of/)).toBeDefined();
    expect(screen.getByLabelText('Refreshing')).toBeDefined();

    getReportMock.mockResolvedValue({ ...baseReport, stale: false });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.queryByLabelText('Refreshing')).toBeNull();
  });

  it('shouldShowGenerationError', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      generation: {
        status: 'failed',
        error: 'DynamoDB timeout',
        started_at: baseReport.generated_at - 100,
        finished_at: baseReport.generated_at,
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(
      screen.getByText(/Report generation failed: DynamoDB timeout/),
    ).toBeDefined();
  });
});
