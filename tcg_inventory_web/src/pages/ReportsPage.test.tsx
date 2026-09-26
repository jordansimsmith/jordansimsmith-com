import { render, screen, cleanup, act, within } from '@testing-library/react';
import { MantineProvider } from '@mantine/core';
import { MemoryRouter, Routes, Route } from 'react-router-dom';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { ReportsPage } from './ReportsPage';
import * as clientModule from '../api/client';
import type {
  ReportGame,
  Report,
  ReportResponse,
  ReportTotals,
} from '../api/client';
import finishClasses from '../components/CardFinishName.module.css';

const baseTotals: ReportTotals = {
  inventory_value: '2894.35',
  in_stock_units: 9412,
  sku_count: 6120,
  reserved_units: 14,
  sold_units: 862,
  revenue_to_date: '1204.50',
  unpriced_units: 3,
};

const baseGameReport: ReportGame = {
  game: 'mtg',
  unique_card_names: 5800,
  totals: baseTotals,
  top_hits: [],
  top_sets: [
    { set_code: 'cmr', set_name: 'Commander Legends', in_stock_units: 11 },
    {
      set_code: 'sta',
      set_name: 'Strixhaven Mystical Archive',
      in_stock_units: 8,
    },
    { set_code: 'a25', set_name: 'Masters 25', in_stock_units: 5 },
  ],
  aging_bands: [
    { label: '0-30 days', in_stock_units: 22 },
    { label: '31-90 days', in_stock_units: 35 },
    { label: '91-180 days', in_stock_units: 25 },
    { label: '180+ days', in_stock_units: 12 },
  ],
  price_buckets: [
    { label: '$0.25-$0.50', in_stock_units: 38 },
    { label: '$0.50-$1', in_stock_units: 24 },
    { label: '$1-$2', in_stock_units: 15 },
    { label: '$2-$5', in_stock_units: 9 },
    { label: '$5-$10', in_stock_units: 5 },
    { label: '$10+', in_stock_units: 3 },
  ],
};

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
    totals: baseTotals,
    revenue_by_month: [
      { month: '2026-03', revenue: '124.50', order_count: 8 },
      { month: '2026-04', revenue: '287.00', order_count: 15 },
      { month: '2026-05', revenue: '195.75', order_count: 12 },
    ],
    intake_vs_sales_by_week: [
      { week_start: '2026-06-01', added_units: 12, sold_units: 3 },
      { week_start: '2026-06-08', added_units: 8, sold_units: 5 },
    ],
    games: [baseGameReport],
  },
};

function reportWithOverrides(
  gameOverrides: Partial<ReportGame> = {},
  reportOverrides: Partial<Report> = {},
): ReportResponse {
  return {
    ...baseReport,
    report: {
      ...baseReport.report,
      ...reportOverrides,
      games: baseReport.report.games.map((game) => ({
        ...game,
        ...gameOverrides,
      })),
    },
  };
}

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

  it('shouldRenderSummaryStrip', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
    });

    renderReportsPage();
    await act(async () => {});

    expect(
      screen.getByRole('region', { name: 'Inventory summary' }),
    ).toBeDefined();
    const inventoryValue = screen.getByRole('group', {
      name: 'In-stock inventory value',
    });
    expect(within(inventoryValue).getByText('$2,894.35')).toBeDefined();
    expect(screen.getByText('9,412 in stock')).toBeDefined();
    expect(screen.getByText('6,120 SKUs')).toBeDefined();
    expect(screen.getByText('14 reserved')).toBeDefined();
    expect(
      screen.getByRole('group', { name: 'Paid order revenue' }),
    ).toBeDefined();
    expect(screen.getByText('862 units sold to date')).toBeDefined();
    expect(screen.getAllByText('$1,204.50')).toHaveLength(2);
    expect(
      screen.getByText('3 unpriced units excluded from value'),
    ).toBeDefined();
  });

  it('shouldHideUnpricedItemWhenZero', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides(
        {},
        {
          totals: { ...baseReport.report.totals, unpriced_units: 0 },
        },
      ),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.queryByText(/unpriced units excluded/)).toBeNull();
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
    expect(screen.getByLabelText('Loading report')).toBeDefined();
    expect(screen.queryByText(/Data as of/)).toBeNull();
    expect(screen.queryByText('No report data yet.')).toBeNull();

    getReportMock.mockResolvedValue({ ...baseReport });

    await act(async () => {
      await vi.advanceTimersByTimeAsync(2000);
    });

    expect(screen.getByText(/Data as of/)).toBeDefined();
    expect(screen.getAllByText('$2,894.35')).toHaveLength(2);
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

  it('shouldRenderGameTabsAndTopSetsList', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue({
      ...baseReport,
    });

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByRole('tablist', { name: 'Report game' })).toBeDefined();
    expect(
      screen.getByRole('tab', { name: 'Magic: The Gathering' }),
    ).toBeDefined();
    const gameSummary = screen.getByRole('region', {
      name: 'Magic: The Gathering inventory summary',
    });
    const summary = within(gameSummary);
    expect(
      summary
        .getAllByRole('group')
        .map((group) => group.getAttribute('aria-label')),
    ).toEqual(['Inventory value', 'Unique card names', 'Paid revenue']);
    expect(
      within(summary.getByRole('group', { name: 'Inventory value' })).getByText(
        '$2,894.35',
      ),
    ).toBeDefined();
    const uniqueCardNames = summary.getByRole('group', {
      name: 'Unique card names',
    });
    expect(within(uniqueCardNames).getByText('5,800')).toBeDefined();
    expect(
      within(uniqueCardNames).getByText('9,412 units in stock'),
    ).toBeDefined();
    const paidRevenue = summary.getByRole('group', { name: 'Paid revenue' });
    expect(within(paidRevenue).getByText('$1,204.50')).toBeDefined();
    expect(within(paidRevenue).getByText('862 units sold')).toBeDefined();
    expect(summary.queryByText('SKUs')).toBeNull();
    expect(summary.queryByText('Reserved')).toBeNull();
    expect(summary.queryByText('Unpriced')).toBeNull();
    expect(screen.getByText('Largest sets in stock')).toBeDefined();
    expect(screen.queryByText('No sets with cards in stock.')).toBeNull();
  });

  it('shouldShowTopSetsEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({ top_sets: [] }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Largest sets in stock')).toBeDefined();
    expect(screen.getByText('No sets with cards in stock.')).toBeDefined();
  });

  it('shouldRenderPriceBucketsChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({
        price_buckets: [
          { label: '$0.25-$0.50', in_stock_units: 38 },
          { label: '$0.50-$1', in_stock_units: 24 },
          { label: '$1-$2', in_stock_units: 15 },
          { label: '$2-$5', in_stock_units: 9 },
          { label: '$5-$10', in_stock_units: 5 },
          { label: '$10+', in_stock_units: 3 },
        ],
      }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Stock by price')).toBeDefined();
    expect(screen.queryByText('No in-stock cards have a price.')).toBeNull();
  });

  it('shouldShowPriceBucketsEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({ price_buckets: [] }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Stock by price')).toBeDefined();
    expect(screen.getByText('No in-stock cards have a price.')).toBeDefined();
  });

  it('shouldRenderTopHitsTable', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({
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
          {
            sku_id: 'sku3#etched#MP',
            name: 'Jeska, Thrice Reborn',
            set_code: 'cmr',
            collector_number: '186',
            finish: 'etched',
            condition: 'MP',
            price: '12.00',
            in_stock_units: 1,
          },
        ],
      }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Highest-value cards')).toBeDefined();
    expect(screen.getByText('Ragavan, Nimble Pilferer')).toBeDefined();
    expect(
      screen
        .getByText('Ragavan, Nimble Pilferer')
        .closest('td')
        ?.getAttribute('data-field'),
    ).toBe('name');
    expect(screen.getByText('$95.00')).toBeDefined();
    expect(screen.getByText('Doubling Season')).toBeDefined();
    expect(screen.getByText('$48.50')).toBeDefined();
    expect(screen.getByText('Rank')).toBeDefined();
    expect(screen.getByText('Name')).toBeDefined();
    expect(screen.getByText('Set / no.')).toBeDefined();
    expect(screen.getByText('Finish')).toBeDefined();
    expect(screen.getByText('Condition')).toBeDefined();
    expect(screen.getByText('Unit price')).toBeDefined();
    expect(screen.getByText('NM')).toBeDefined();
    expect(screen.getByText('LP')).toBeDefined();
    expect(screen.getByText('MP')).toBeDefined();
    expect(screen.getByText('NM').style.fontWeight).toBe('');
    expect(screen.getByText('LP').style.fontWeight).toBe('');
    expect(screen.getByText('NM').style.color).toBe('');
    expect(screen.getByText('LP').style.color).toBe('');
    const rankCells = screen
      .getAllByRole('cell')
      .filter((cell) => cell.getAttribute('data-field') === 'rank');
    expect(rankCells.map((cell) => cell.textContent)).toEqual(['1', '2', '3']);
    expect(screen.getByText('MH2#138')).toBeDefined();
    expect(screen.getByText('BBD#195')).toBeDefined();
    expect(screen.getByText('CMR#186')).toBeDefined();
    expect(screen.getByText('normal')).toBeDefined();
    expect(screen.getByText('foil')).toBeDefined();
    expect(screen.getByText('etched')).toBeDefined();
    expect(screen.getByText('normal').style.fontWeight).toBe('');
    expect(screen.getByText('foil').style.fontWeight).toBe('');
    expect(screen.getByText('etched').style.fontWeight).toBe('');
    const headers = screen
      .getAllByRole('columnheader')
      .map((header) => header.textContent);
    expect(headers.indexOf('Finish')).toBe(headers.indexOf('Set / no.') + 1);
    expect(headers.indexOf('Condition')).toBe(headers.indexOf('Finish') + 1);
    expect(
      screen
        .getByText('Doubling Season')
        .classList.contains(finishClasses.foil),
    ).toBe(true);
    expect(screen.getByText('Doubling Season').style.fontWeight).toBe('700');
    expect(
      screen
        .getByText('Jeska, Thrice Reborn')
        .classList.contains(finishClasses.etched),
    ).toBe(true);
    expect(screen.getByText('Jeska, Thrice Reborn').style.fontWeight).toBe(
      '700',
    );
    expect(
      screen
        .getByText('Ragavan, Nimble Pilferer')
        .classList.contains(finishClasses.foil),
    ).toBe(false);
    expect(screen.getByText('Ragavan, Nimble Pilferer').style.fontWeight).toBe(
      '500',
    );
  });

  it('shouldShowTopHitsEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({ top_hits: [] }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Highest-value cards')).toBeDefined();
    expect(screen.getByText('No priced cards in stock.')).toBeDefined();
  });

  it('shouldRenderStockAgingChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({
        aging_bands: [
          { label: '0-30 days', in_stock_units: 22 },
          { label: '31-90 days', in_stock_units: 35 },
          { label: '91-180', in_stock_units: 25 },
          { label: '180+', in_stock_units: 12 },
        ],
      }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Time in stock')).toBeDefined();
    expect(screen.getByText('94 in-stock units')).toBeDefined();
    expect(screen.getByText('12 (13%)')).toBeDefined();
    expect(screen.getByText('0-30 days')).toBeDefined();
    expect(screen.getByText('22 (23%)')).toBeDefined();
  });

  it('shouldShowStockAgingEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides({ aging_bands: [] }),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Time in stock')).toBeDefined();
    expect(screen.getByText('No cards in stock.')).toBeDefined();
  });

  it('shouldRenderRevenueByMonthChart', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides(
        {},
        {
          revenue_by_month: [
            { month: '2026-06', revenue: '342.20', order_count: 18 },
            { month: '2026-07', revenue: '156.80', order_count: 9 },
          ],
        },
      ),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Monthly revenue')).toBeDefined();
    expect(screen.queryByText('No paid orders yet.')).toBeNull();
  });

  it('shouldShowRevenueEmptyMessageWhenEmpty', async () => {
    vi.spyOn(clientModule.apiClient, 'getReport').mockResolvedValue(
      reportWithOverrides(
        {},
        {
          revenue_by_month: [],
        },
      ),
    );

    renderReportsPage();
    await act(async () => {});

    expect(screen.getByText('Monthly revenue')).toBeDefined();
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

    expect(screen.getByText('Weekly card movement')).toBeDefined();
    expect(screen.queryByText('No card movement yet.')).toBeNull();
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

    expect(screen.getByText('Weekly card movement')).toBeDefined();
    expect(screen.getByText('No card movement yet.')).toBeDefined();
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
