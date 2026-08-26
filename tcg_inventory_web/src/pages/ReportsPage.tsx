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
  Title,
} from '@mantine/core';
import { BarChart, LineChart } from '@mantine/charts';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
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

// fresh-to-stale ramp for the aging bands, in band order
const AGING_HUES = ['green', 'blue', 'yellow', 'red'];

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

// the two hero tones bookend the strip: blue for held value, teal for banked
// revenue, matching the revenue chart hue
const STAT_TONES = {
  value: {
    background: 'var(--mantine-color-blue-light)',
    color: 'blue.8',
    hero: true,
  },
  revenue: {
    background: 'var(--mantine-color-teal-light)',
    color: 'teal.8',
    hero: true,
  },
  warning: {
    background: 'var(--mantine-color-yellow-light)',
    color: 'yellow.9',
    hero: false,
  },
} as const;

function StatCard({
  label,
  value,
  sub,
  tone,
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: keyof typeof STAT_TONES;
}) {
  const style = tone ? STAT_TONES[tone] : undefined;
  return (
    <Paper p="md" radius="sm" withBorder bg={style?.background}>
      <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
        {label}
      </Text>
      <Text
        fz={style?.hero ? 26 : 20}
        fw={style?.hero ? 800 : 700}
        lh={1.3}
        mt={4}
        c={style?.color}
      >
        {value}
      </Text>
      {sub && (
        <Text size="xs" c="dimmed" mt={4}>
          {sub}
        </Text>
      )}
    </Paper>
  );
}

function FigureTitle({ title, subtitle }: { title: string; subtitle: string }) {
  return (
    <Group justify="space-between" align="baseline" gap="xs" mb="md">
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
    <SimpleGrid cols={{ base: 2, sm: 3, md: 6 }} spacing="md">
      <StatCard
        label="Inventory value (NZD)"
        value={wholeCurrencyFormat.format(parseFloat(totals.inventory_value))}
        sub="at your listed prices"
        tone="value"
      />
      <StatCard
        label="In stock"
        value={totals.in_stock_units.toLocaleString()}
      />
      <StatCard label="SKUs" value={totals.sku_count.toLocaleString()} />
      <StatCard
        label="Reserved"
        value={totals.reserved_units.toLocaleString()}
      />
      <StatCard
        label="Sold · all-time"
        value={totals.sold_units.toLocaleString()}
      />
      <StatCard
        label="Revenue · all-time"
        value={formatCurrency(totals.revenue_to_date)}
        sub="from paid orders"
        tone="revenue"
      />
      {totals.unpriced_units > 0 && (
        <StatCard
          label="Unpriced"
          value={totals.unpriced_units.toLocaleString()}
          sub="excluded from value"
          tone="warning"
        />
      )}
    </SimpleGrid>
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
    <Paper p="md" radius="sm" withBorder>
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
    <Paper p="md" radius="sm" withBorder>
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
    <Paper p="md" radius="sm" withBorder>
      <FigureTitle title="Top hits" subtitle="in-stock cards by unit price" />
      {topHits.length === 0 ? (
        <Text size="sm" c="dimmed">
          No in-stock hits yet.
        </Text>
      ) : (
        <Table
          striped
          highlightOnHover
          verticalSpacing={4}
          horizontalSpacing="sm"
          fz="sm"
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
                <Table.Td c="dimmed">{index + 1}</Table.Td>
                <Table.Td fw={500}>{hit.name}</Table.Td>
                <Table.Td>{hit.set_code.toUpperCase()}</Table.Td>
                <Table.Td
                  c={hit.finish === 'normal' ? 'dimmed' : undefined}
                  fw={hit.finish === 'normal' ? undefined : 600}
                  tt="capitalize"
                >
                  {hit.finish === 'normal' ? '—' : hit.finish}
                </Table.Td>
                <Table.Td
                  c={hit.condition === 'NM' ? 'dimmed' : undefined}
                  fw={hit.condition === 'NM' ? undefined : 600}
                >
                  {hit.condition}
                </Table.Td>
                <Table.Td
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
    <Paper p="md" radius="sm" withBorder>
      <FigureTitle
        title="Stock aging"
        subtitle="in-stock units by days since intake"
      />
      {total === 0 ? (
        <Text size="sm" c="dimmed">
          No in-stock units.
        </Text>
      ) : (
        <Stack gap="sm">
          <Group justify="space-between">
            <Text size="sm" c="dimmed">
              {total.toLocaleString()} units
            </Text>
            <Text
              size="sm"
              fw={600}
              c={oldest.in_stock_units > 0 ? 'red.7' : 'dimmed'}
            >
              {oldest.label}:{' '}
              {Math.round((oldest.in_stock_units / total) * 100)}% of stock
            </Text>
          </Group>
          <Progress.Root size={18} radius="sm">
            {agingBands.map((band, index) => (
              <Progress.Section
                key={band.label}
                value={(band.in_stock_units / total) * 100}
                color={`${AGING_HUES[index] ?? 'gray'}.6`}
              />
            ))}
          </Progress.Root>
          <Group gap="lg">
            {agingBands.map((band, index) => (
              <Group key={band.label} gap={6}>
                <ColorSwatch
                  size={10}
                  color={`var(--mantine-color-${AGING_HUES[index] ?? 'gray'}-6)`}
                />
                <Text size="xs" c="dimmed">
                  {band.label} · {band.in_stock_units.toLocaleString()} (
                  {Math.round((band.in_stock_units / total) * 100)}%)
                </Text>
              </Group>
            ))}
          </Group>
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
    <Paper p="md" radius="sm" withBorder>
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
    <Paper p="md" radius="sm" withBorder>
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
          h={300}
          data={data}
          dataKey="label"
          series={[
            { name: 'in_stock_units', label: 'In stock', color: 'blue.6' },
          ]}
          gridAxis="y"
          tickLine="y"
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
        <Group justify="space-between" align="center">
          <Title order={2}>Reports</Title>
          <Group gap="sm" align="center">
            {refreshing && (
              <Group gap={6} align="center">
                <Loader size="xs" aria-label="Refreshing" />
                <Text size="sm" c="dimmed">
                  Refreshing…
                </Text>
              </Group>
            )}
            {report && (
              <Text size="sm" c="dimmed">
                Data as of {formatGeneratedAt(report.generated_at)}
              </Text>
            )}
          </Group>
        </Group>

        {report?.generation?.status === 'failed' && (
          <Text c="red">
            Report generation failed: {report.generation.error}
          </Text>
        )}

        {firstVisit && (
          <Stack gap="xs">
            <Skeleton height={20} width={280} />
            <Skeleton height={120} />
            <Skeleton height={120} />
          </Stack>
        )}

        {!firstVisit && report?.report?.totals && (
          <>
            <TotalsStrip totals={report.report.totals} />

            {/* columns mirror the hero bookends: stock on the left, money on the right */}
            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              <IntakeVsSalesChart
                intakeVsSales={report.report.intake_vs_sales_by_week ?? []}
              />
              <RevenueByMonthChart
                revenueByMonth={report.report.revenue_by_month ?? []}
              />
            </SimpleGrid>

            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
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
