import { useCallback, useEffect, useRef, useState } from 'react';
import {
  ColorSwatch,
  Group,
  Loader,
  Paper,
  Progress,
  SimpleGrid,
  Skeleton,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { BarChart, LineChart } from '@mantine/charts';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { JobFailureAlert } from '../components/JobFailureAlert';
import { PageHeader } from '../components/PageHeader';
import finishClasses from '../components/CardFinishName.module.css';
import { apiClient } from '../api/client';
import { finishNameWeight, formatSetNumber } from '../domain/card-label';
import type {
  ReportAgingBand,
  ReportIntakeVsSales,
  ReportPriceBucket,
  ReportResponse,
  ReportRevenueByMonth,
  ReportTopHit,
  ReportTopSet,
  ReportTotals,
} from '../api/client';
import classes from './ReportsPage.module.css';

dayjs.extend(relativeTime);

const POLL_INTERVAL_MS = 2000;
const TWENTY_FOUR_HOURS_S = 24 * 60 * 60;

const currencyFormat = new Intl.NumberFormat('en-NZ', {
  style: 'currency',
  currency: 'NZD',
});

const wholeCurrencyFormat = new Intl.NumberFormat('en-NZ', {
  style: 'currency',
  currency: 'NZD',
  maximumFractionDigits: 0,
});

const AGING_SHADES = [3, 5, 7, 9];

function formatGeneratedAt(epochSeconds: number): string {
  const now = Math.floor(Date.now() / 1000);
  if (now - epochSeconds < TWENTY_FOUR_HOURS_S) {
    return dayjs(epochSeconds * 1000).fromNow();
  }
  return new Date(epochSeconds * 1000).toLocaleString();
}

function isGenerationActive(report: ReportResponse): boolean {
  return (
    report.generation?.status === 'queued' ||
    report.generation?.status === 'running'
  );
}

function formatCurrency(value: string | number): string {
  return currencyFormat.format(
    typeof value === 'number' ? value : parseFloat(value),
  );
}

function FigureTitle({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <Group
      className={classes.figureHeader}
      justify="space-between"
      align="baseline"
      gap="xs"
      mb="md"
    >
      <Text size="sm" fw={700}>
        {title}
      </Text>
      <Text size="xs" c="dimmed">
        {subtitle}
      </Text>
    </Group>
  );
}

function ChartTooltip({ title, detail }: { title: string; detail: string }) {
  return (
    <Paper px="sm" py={6} radius="sm" withBorder shadow="sm">
      <Text size="sm" fw={600}>
        {title}
      </Text>
      <Text size="sm">{detail}</Text>
    </Paper>
  );
}

function TotalsStrip({ totals }: { totals: ReportTotals }) {
  return (
    <Paper
      component="section"
      aria-label="Inventory summary"
      withBorder
      radius="md"
      p={0}
      className={classes.overview}
    >
      <div
        className={classes.overviewGroup}
        role="group"
        aria-label="Inventory at listed prices"
      >
        <Text size="sm" c="dimmed" fw={600}>
          Inventory at listed prices
        </Text>
        <Text className={classes.overviewValue}>
          {wholeCurrencyFormat.format(parseFloat(totals.inventory_value))}
        </Text>
        <Text size="sm" className={classes.overviewFacts}>
          <span>{totals.in_stock_units.toLocaleString()} in stock</span> ·{' '}
          <span>{totals.sku_count.toLocaleString()} SKUs</span> ·{' '}
          <span>{totals.reserved_units.toLocaleString()} reserved</span>
        </Text>
        {totals.unpriced_units > 0 && (
          <Text size="xs" c="dimmed" mt="xs">
            {totals.unpriced_units.toLocaleString()} unpriced{' '}
            {totals.unpriced_units === 1 ? 'unit' : 'units'} excluded from value
          </Text>
        )}
      </div>
      <div
        className={classes.overviewGroup}
        role="group"
        aria-label="Sales from paid orders"
      >
        <Text size="sm" c="dimmed" fw={600}>
          Sales from paid orders
        </Text>
        <Text className={classes.overviewValue}>
          {formatCurrency(totals.revenue_to_date)}
        </Text>
        <Text size="sm" className={classes.overviewFacts}>
          {totals.sold_units.toLocaleString()} sold all-time
        </Text>
      </div>
    </Paper>
  );
}

function RevenueByMonthChart({
  revenueByMonth,
}: {
  revenueByMonth: ReportRevenueByMonth[];
}) {
  const data = revenueByMonth.map((entry) => ({
    month: dayjs(`${entry.month}-01`).format('MMM YYYY'),
    revenue: parseFloat(entry.revenue),
    order_count: entry.order_count,
  }));

  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle title="Revenue by month" subtitle="NZD · paid orders only" />
      {data.length === 0 ? (
        <Text size="sm" c="dimmed">
          No paid orders yet.
        </Text>
      ) : (
        <BarChart
          h={300}
          data={data}
          dataKey="month"
          series={[{ name: 'revenue', label: 'Revenue', color: 'teal.6' }]}
          gridAxis="y"
          tickLine="y"
          valueFormatter={(v) => wholeCurrencyFormat.format(v)}
          tooltipProps={{
            content: ({ payload }) => {
              const datum = payload?.[0]?.payload as
                | (typeof data)[number]
                | undefined;
              if (!datum) return null;
              return (
                <ChartTooltip
                  title={datum.month}
                  detail={`${formatCurrency(datum.revenue)} · ${datum.order_count} ${
                    datum.order_count === 1 ? 'order' : 'orders'
                  }`}
                />
              );
            },
          }}
        />
      )}
    </Paper>
  );
}

function IntakeVsSalesChart({
  intakeVsSales,
}: {
  intakeVsSales: ReportIntakeVsSales[];
}) {
  const data = intakeVsSales.map((entry) => ({
    week: dayjs(entry.week_start).format('D MMM'),
    added_units: entry.added_units,
    sold_units: entry.sold_units,
  }));

  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle title="Intake vs sales" subtitle="units per week" />
      {data.length === 0 ? (
        <Text size="sm" c="dimmed">
          No weekly activity yet.
        </Text>
      ) : (
        <LineChart
          h={300}
          data={data}
          dataKey="week"
          series={[
            { name: 'added_units', label: 'Added', color: 'blue.6' },
            { name: 'sold_units', label: 'Sold', color: 'teal.6' },
          ]}
          withLegend
          curveType="monotone"
          gridAxis="y"
          tickLine="y"
        />
      )}
    </Paper>
  );
}

function TopHitsTable({ topHits }: { topHits: ReportTopHit[] }) {
  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle title="Top hits" subtitle="in-stock cards by unit price" />
      {topHits.length === 0 ? (
        <Text size="sm" c="dimmed">
          No in-stock hits yet.
        </Text>
      ) : (
        <Table
          highlightOnHover
          verticalSpacing={4}
          horizontalSpacing="sm"
          fz="sm"
          className={classes.topHitsTable}
        >
          <Table.Thead>
            <Table.Tr>
              <Table.Th>#</Table.Th>
              <Table.Th>Name</Table.Th>
              <Table.Th>Set</Table.Th>
              <Table.Th>Finish</Table.Th>
              <Table.Th>Condition</Table.Th>
              <Table.Th ta="right">Price</Table.Th>
            </Table.Tr>
          </Table.Thead>
          <Table.Tbody>
            {topHits.map((hit, index) => (
              <Table.Tr key={hit.sku_id}>
                <Table.Td data-field="rank" c="dimmed">
                  {index + 1}
                </Table.Td>
                <Table.Td
                  data-field="name"
                  className={finishClasses[hit.finish]}
                  fw={finishNameWeight(hit.finish)}
                >
                  {hit.name}
                </Table.Td>
                <Table.Td data-field="set" data-label="Set">
                  {formatSetNumber(hit.set_code, hit.collector_number)}
                </Table.Td>
                <Table.Td
                  data-field="finish"
                  data-label="Finish"
                  tt="capitalize"
                >
                  {hit.finish}
                </Table.Td>
                <Table.Td data-field="condition" data-label="Condition">
                  {hit.condition}
                </Table.Td>
                <Table.Td
                  data-field="price"
                  ta="right"
                  style={{ fontVariantNumeric: 'tabular-nums' }}
                >
                  {formatCurrency(hit.price)}
                </Table.Td>
              </Table.Tr>
            ))}
          </Table.Tbody>
        </Table>
      )}
    </Paper>
  );
}

function StockAgingFigure({ agingBands }: { agingBands: ReportAgingBand[] }) {
  const total = agingBands.reduce((sum, band) => sum + band.in_stock_units, 0);
  const oldest = agingBands[agingBands.length - 1];

  return (
    <Paper p="md" radius="md" withBorder className={classes.agingPanel}>
      <FigureTitle
        title="Stock aging"
        subtitle="in-stock units by days since intake"
      />
      {total === 0 ? (
        <Text size="sm" c="dimmed">
          No in-stock units.
        </Text>
      ) : (
        <Stack gap="sm" className={classes.agingContent}>
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {total.toLocaleString()} units
            </Text>
            <Text size="sm" fw={600} c="dimmed">
              {oldest.label}:{' '}
              {Math.round((oldest.in_stock_units / total) * 100)}% of stock
            </Text>
          </Group>
          <Progress.Root size={18} radius="sm">
            {agingBands.map((band, index) => (
              <Progress.Section
                key={band.label}
                value={(band.in_stock_units / total) * 100}
                color={`blue.${AGING_SHADES[index] ?? 9}`}
              />
            ))}
          </Progress.Root>
          <div className={classes.agingLegend}>
            {agingBands.map((band, index) => (
              <div className={classes.agingRow} key={band.label}>
                <Group gap="xs" wrap="nowrap">
                  <ColorSwatch
                    size={10}
                    color={`var(--mantine-color-blue-${AGING_SHADES[index] ?? 9})`}
                  />
                  <Text size="sm">{band.label}</Text>
                </Group>
                <Text size="sm" className={classes.agingCount}>
                  {band.in_stock_units.toLocaleString()} (
                  {Math.round((band.in_stock_units / total) * 100)}%)
                </Text>
              </div>
            ))}
          </div>
        </Stack>
      )}
    </Paper>
  );
}

function TopSetsChart({ topSets }: { topSets: ReportTopSet[] }) {
  const data = topSets.map((s) => ({
    code: s.set_code.toUpperCase(),
    set_name: s.set_name,
    in_stock_units: s.in_stock_units,
  }));

  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle title="Top sets" subtitle="in-stock units" />
      {data.length === 0 ? (
        <Text size="sm" c="dimmed">
          No sets in stock.
        </Text>
      ) : (
        <BarChart
          h={300}
          data={data}
          dataKey="code"
          series={[
            { name: 'in_stock_units', label: 'In stock', color: 'blue.6' },
          ]}
          orientation="vertical"
          gridAxis="x"
          tickLine="x"
          yAxisProps={{ width: 52 }}
          tooltipProps={{
            content: ({ payload }) => {
              const datum = payload?.[0]?.payload as
                | (typeof data)[number]
                | undefined;
              if (!datum) return null;
              return (
                <ChartTooltip
                  title={datum.set_name}
                  detail={`${datum.in_stock_units.toLocaleString()} in stock`}
                />
              );
            },
          }}
        />
      )}
    </Paper>
  );
}

function PriceBucketsChart({
  priceBuckets,
}: {
  priceBuckets: ReportPriceBucket[];
}) {
  const data = priceBuckets.map((b) => ({
    label: b.label,
    in_stock_units: b.in_stock_units,
  }));

  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle
        title="Price distribution"
        subtitle="in-stock units by listing price"
      />
      {data.length === 0 ? (
        <Text size="sm" c="dimmed">
          No priced units in stock.
        </Text>
      ) : (
        <BarChart
          h={270}
          data={data}
          dataKey="label"
          series={[
            { name: 'in_stock_units', label: 'In stock', color: 'blue.6' },
          ]}
          orientation="vertical"
          gridAxis="x"
          tickLine="x"
          yAxisProps={{ width: 96 }}
          tooltipProps={{
            content: ({ payload }) => {
              const datum = payload?.[0]?.payload as
                | (typeof data)[number]
                | undefined;
              if (!datum) return null;
              return (
                <ChartTooltip
                  title={datum.label}
                  detail={`${datum.in_stock_units.toLocaleString()} in stock`}
                />
              );
            },
          }}
        />
      )}
    </Paper>
  );
}

export function ReportsPage() {
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [firstVisit, setFirstVisit] = useState(false);
  const [refreshing, setRefreshing] = useState(false);
  const [pollEpoch, setPollEpoch] = useState(0);
  const cancelledRef = useRef(false);
  const timerRef = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  const fetchAndHandle = useCallback(async () => {
    try {
      const response = await apiClient.getReport();
      if (cancelledRef.current) return;

      setReport(response);
      setFirstVisit(false);

      if (response.stale && !isGenerationActive(response)) {
        setRefreshing(true);
        await apiClient.createReport();
        if (cancelledRef.current) return;
        timerRef.current = setTimeout(
          () => setPollEpoch((e) => e + 1),
          POLL_INTERVAL_MS,
        );
      } else if (isGenerationActive(response)) {
        setRefreshing(true);
        timerRef.current = setTimeout(
          () => setPollEpoch((e) => e + 1),
          POLL_INTERVAL_MS,
        );
      } else {
        setRefreshing(false);
      }
    } catch (e) {
      if (cancelledRef.current) return;
      const message = e instanceof Error ? e.message : '';
      if (message === 'Not Found') {
        setFirstVisit(true);
        await apiClient.createReport();
        if (cancelledRef.current) return;
        timerRef.current = setTimeout(
          () => setPollEpoch((e) => e + 1),
          POLL_INTERVAL_MS,
        );
      }
    }
  }, []);

  useEffect(() => {
    cancelledRef.current = false;
    clearTimeout(timerRef.current);
    fetchAndHandle();
    return () => {
      cancelledRef.current = true;
      clearTimeout(timerRef.current);
    };
  }, [pollEpoch, fetchAndHandle]);

  useEffect(() => {
    const handleVisibilityChange = () => {
      if (document.visibilityState === 'visible') {
        setPollEpoch((e) => e + 1);
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, []);

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Reports"
          description={
            report
              ? `Data as of ${formatGeneratedAt(report.generated_at)}`
              : 'Inventory value, movement, and stock composition.'
          }
          actions={
            refreshing && (
              <Group gap={6} align="center">
                <Loader size="xs" aria-label="Refreshing" />
                <Text size="sm" c="dimmed">
                  Refreshing…
                </Text>
              </Group>
            )
          }
        />

        {report?.generation?.status === 'failed' && (
          <JobFailureAlert
            title="Report generation failed"
            error={report.generation.error}
            maw={480}
          />
        )}

        {firstVisit && (
          <Stack gap="md" aria-label="Loading report">
            <Paper withBorder radius="md" p={0} className={classes.overview}>
              {[0, 1].map((index) => (
                <div className={classes.overviewGroup} key={index}>
                  <Skeleton height={12} width="75%" />
                  <Skeleton height={30} width="45%" mt="sm" />
                  <Skeleton height={14} width="65%" mt="sm" />
                </div>
              ))}
            </Paper>
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              {[0, 1].map((item) => (
                <Paper key={item} withBorder radius="md" p="md">
                  <Skeleton height={16} width={160} mb="lg" />
                  <Skeleton height={250} />
                </Paper>
              ))}
            </SimpleGrid>
          </Stack>
        )}

        {!firstVisit && report?.report?.totals && (
          <>
            <TotalsStrip totals={report.report.totals} />

            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              <IntakeVsSalesChart
                intakeVsSales={report.report.intake_vs_sales_by_week ?? []}
              />
              <RevenueByMonthChart
                revenueByMonth={report.report.revenue_by_month ?? []}
              />
            </SimpleGrid>

            <SimpleGrid cols={{ base: 1, xl: 2 }} spacing="md">
              <TopHitsTable topHits={report.report.top_hits ?? []} />
              <StockAgingFigure agingBands={report.report.aging_bands ?? []} />
            </SimpleGrid>

            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              <TopSetsChart topSets={report.report.top_sets ?? []} />
              <PriceBucketsChart
                priceBuckets={report.report.price_buckets ?? []}
              />
            </SimpleGrid>
          </>
        )}

        {!firstVisit && report && !report.report?.totals && (
          <Text c="dimmed">No report data yet.</Text>
        )}
      </Stack>
    </AppShellLayout>
  );
}
