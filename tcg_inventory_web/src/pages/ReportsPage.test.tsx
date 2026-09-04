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
  report: {
    totals: {
      inventory_value: '2894.35',
      in_stock_units: 9412,
      sku_count: 6120,
      reserved_units: 14,
      sold_units: 862,
      revenue_to_date: '1204.50',
      unpriced_units: 3,
    },
    top_sets: [
      { set_code: 'cmr', set_name: 'Commander Legends', in_stock_units: 11 },
      {
        set_code: 'sta',
        set_name: 'Strixhaven Mystical Archive',
        in_stock_units: 8,
      },
      { set_code: 'a25', set_name: 'Masters 25', in_stock_units: 5 },
    ],
  },
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

  it('shouldRenderStatCards', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('$2,894')).toBeDefined();
    expect(screen.getByText('at your listed prices')).toBeDefined();
    expect(screen.getByText('9,412')).toBeDefined();
    expect(screen.getByText('6,120')).toBeDefined();
    expect(screen.getByText('14')).toBeDefined();
    expect(screen.getByText('Sold · all-time')).toBeDefined();
    expect(screen.getByText('862')).toBeDefined();
    expect(screen.getByText('Revenue · all-time')).toBeDefined();
    expect(screen.getByText('$1,204.50')).toBeDefined();
    expect(screen.getByText('from paid orders')).toBeDefined();
    expect(screen.getByText('3')).toBeDefined();
    expect(screen.getByText('Unpriced')).toBeDefined();
    expect(screen.getByText('excluded from value')).toBeDefined();
  });

  it('shouldHideUnpricedCardWhenZero', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        totals: {
          ...baseReport.report.totals!,
          unpriced_units: 0,
        },
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.queryByText('Unpriced')).toBeNull();
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
    expect(screen.getByText('$2,894')).toBeDefined();
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

  it('shouldRenderTopSetsChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Top sets')).toBeDefined();
    expect(screen.queryByText('No sets in stock.')).toBeNull();
  });

  it('shouldShowTopSetsEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        top_sets: [],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Top sets')).toBeDefined();
    expect(screen.getByText('No sets in stock.')).toBeDefined();
  });

  it('shouldRenderPriceBucketsChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        price_buckets: [
          { label: '$0.25-$0.50', in_stock_units: 38 },
          { label: '$0.50-$1', in_stock_units: 24 },
          { label: '$1-$2', in_stock_units: 15 },
          { label: '$2-$5', in_stock_units: 9 },
          { label: '$5-$10', in_stock_units: 5 },
          { label: '$10+', in_stock_units: 3 },
        ],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Price distribution')).toBeDefined();
    expect(screen.queryByText('No priced units in stock.')).toBeNull();
  });

  it('shouldShowPriceBucketsEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        price_buckets: [],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Price distribution')).toBeDefined();
    expect(screen.getByText('No priced units in stock.')).toBeDefined();
  });

  it('shouldRenderTopHitsTable', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        top_hits: [
          {
            sku_id: 'sku1#normal#NM',
            name: 'Ragavan, Nimble Pilferer',
            set_code: 'mh2',
            collector_number: '138',
            finish: 'normal',
            condition: 'NM',
            price: '95.00',
            in_stock_units: 1,
          },
          {
            sku_id: 'sku2#foil#LP',
            name: 'Doubling Season',
            set_code: 'bbd',
            collector_number: '195',
            finish: 'foil',
            condition: 'LP',
            price: '48.50',
            in_stock_units: 2,
          },
        ],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Top hits')).toBeDefined();
    expect(screen.getByText('Ragavan, Nimble Pilferer')).toBeDefined();
    expect(screen.getByText('$95.00')).toBeDefined();
    expect(screen.getByText('Doubling Season')).toBeDefined();
    expect(screen.getByText('$48.50')).toBeDefined();
    expect(screen.getByText('#')).toBeDefined();
    expect(screen.getByText('Name')).toBeDefined();
    expect(screen.getByText('Set')).toBeDefined();
    expect(screen.getByText('Finish')).toBeDefined();
    expect(screen.getByText('Condition')).toBeDefined();
    expect(screen.getByText('Price')).toBeDefined();
    expect(screen.getByText('NM')).toBeDefined();
    expect(screen.getByText('LP')).toBeDefined();
    expect(screen.getByText('NM').style.fontWeight).toBe('');
    expect(screen.getByText('LP').style.fontWeight).toBe('');
    expect(screen.getByText('NM').style.color).toBe('');
    expect(screen.getByText('LP').style.color).toBe('');
    // rank column: two hits ranked 1 and 2
    expect(screen.getByText('1')).toBeDefined();
    expect(screen.getByText('2')).toBeDefined();
    expect(screen.getByText('MH2#138')).toBeDefined();
    expect(screen.getByText('BBD#195')).toBeDefined();
    expect(screen.getByText('normal')).toBeDefined();
    expect(screen.getByText('foil')).toBeDefined();
    expect(screen.getByText('normal').style.fontWeight).toBe('');
    expect(screen.getByText('foil').style.fontWeight).toBe('700');
    const headers = screen
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(headers.indexOf('Finish')).toBe(headers.indexOf('Set') + 1);
    expect(headers.indexOf('Condition')).toBe(headers.indexOf('Finish') + 1);
    expect(
      screen.getByText('Doubling Season').classList.contains('foil-finish'),
    ).toBe(true);
    expect(screen.getByText('Doubling Season').style.fontWeight).toBe('700');
    expect(
      screen
        .getByText('Ragavan, Nimble Pilferer')
        .classList.contains('foil-finish'),
    ).toBe(false);
    expect(screen.getByText('Ragavan, Nimble Pilferer').style.fontWeight).toBe(
      '500',
    );
  });

  it('shouldShowTopHitsEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        top_hits: [],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Top hits')).toBeDefined();
    expect(screen.getByText('No in-stock hits yet.')).toBeDefined();
  });

  it('shouldRenderStockAgingChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        aging_bands: [
          { label: '0-30 days', in_stock_units: 22 },
          { label: '31-90 days', in_stock_units: 35 },
          { label: '91-180', in_stock_units: 25 },
          { label: '180+', in_stock_units: 12 },
        ],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Stock aging')).toBeDefined();
    expect(screen.getByText('94 units')).toBeDefined();
    expect(screen.getByText('180+: 13% of stock')).toBeDefined();
    expect(screen.getByText('0-30 days · 22 (23%)')).toBeDefined();
  });

  it('shouldShowStockAgingEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        aging_bands: [],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Stock aging')).toBeDefined();
    expect(screen.getByText('No in-stock units.')).toBeDefined();
  });

  it('shouldRenderRevenueByMonthChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        revenue_by_month: [
          { month: '2026-06', revenue: '342.20', order_count: 18 },
          { month: '2026-07', revenue: '156.80', order_count: 9 },
        ],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Revenue by month')).toBeDefined();
    expect(screen.queryByText('No paid orders yet.')).toBeNull();
  });

  it('shouldShowRevenueEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        revenue_by_month: [],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Revenue by month')).toBeDefined();
    expect(screen.getByText('No paid orders yet.')).toBeDefined();
  });

  it('shouldRenderIntakeVsSalesChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        intake_vs_sales_by_week: [
          { week_start: '2026-07-06', added_units: 14, sold_units: 6 },
          { week_start: '2026-07-13', added_units: 9, sold_units: 3 },
        ],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Intake vs sales')).toBeDefined();
    expect(screen.queryByText('No weekly activity yet.')).toBeNull();
  });

  it('shouldShowIntakeVsSalesEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
      report: {
        ...baseReport.report,
        intake_vs_sales_by_week: [],
      },
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Intake vs sales')).toBeDefined();
    expect(screen.getByText('No weekly activity yet.')).toBeDefined();
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

    expect(screen.getByText('Report generation failed')).toBeDefined();
    expect(screen.getByText('DynamoDB timeout')).toBeDefined();
  });
});
