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
  Tabs,
  Table,
  Text,
} from '@mantine/core';
import { LineChart } from '@mantine/charts';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { JobFailureAlert } from '../components/JobFailureAlert';
import { PageHeader } from '../components/PageHeader';
import finishClasses from '../components/CardFinishName.module.css';
import { apiClient } from '../api/client';
import { finishNameWeight, formatSetNumber } from '../domain/card-label';
import { GAMES, gameLabel } from '../domain/games';
import type {
  ReportAgingBand,
  ReportGame,
  ReportIntakeVsSales,
  ReportPriceBucket,
  ReportResponse,
  ReportRevenueByMonth,
  ReportTopHit,
  ReportTopSet,
  ReportTotals,
} from '../api/client';
import type { GameId } from '../domain/games';
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

const AGING_SHADES = [3, 4, 5, 6];

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
      <Text component="h2" m={0} size="sm" fw={700}>
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
        aria-label="In-stock inventory value"
      >
        <Text size="sm" c="dimmed" fw={600}>
          In-stock inventory value
        </Text>
        <Text className={classes.overviewValue}>
          {formatCurrency(totals.inventory_value)}
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
        aria-label="Paid order revenue"
      >
        <Text size="sm" c="dimmed" fw={600}>
          Paid order revenue
        </Text>
        <Text className={classes.overviewValue}>
          {formatCurrency(totals.revenue_to_date)}
        </Text>
        <Text size="sm" className={classes.overviewFacts}>
          {totals.sold_units.toLocaleString()} units sold to date
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
      <FigureTitle
        title="Monthly revenue"
        subtitle="Paid orders · NZD, postage excluded"
      />
      {data.length === 0 ? (
        <Text size="sm" c="dimmed">
          No paid orders yet.
        </Text>
      ) : (
        <LineChart
          h={280}
          data={data}
          dataKey="month"
          series={[{ name: 'revenue', label: 'Revenue', color: 'blue.6' }]}
          gridAxis="y"
          tickLine="y"
          curveType="monotone"
          strokeWidth={2}
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
      <FigureTitle
        title="Weekly card movement"
        subtitle="Cards added and sold"
      />
      {data.length === 0 ? (
        <Text size="sm" c="dimmed">
          No card movement yet.
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
      <FigureTitle
        title="Highest-value cards"
        subtitle="In stock · sorted by unit price"
      />
      {topHits.length === 0 ? (
        <Text size="sm" c="dimmed">
          No priced cards in stock.
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
              <Table.Th>Rank</Table.Th>
              <Table.Th>Name</Table.Th>
              <Table.Th>Set / no.</Table.Th>
              <Table.Th>Finish</Table.Th>
              <Table.Th>Condition</Table.Th>
              <Table.Th ta="right">Unit price</Table.Th>
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
                <Table.Td data-field="set" data-label="Set / no.">
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
                  data-label="Unit price"
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

  return (
    <Paper p="md" radius="md" withBorder className={classes.agingPanel}>
      <FigureTitle title="Time in stock" subtitle="Days since intake" />
      {total === 0 ? (
        <Text size="sm" c="dimmed">
          No cards in stock.
        </Text>
      ) : (
        <Stack gap="sm" className={classes.agingContent}>
          <Text size="sm" c="dimmed">
            {total.toLocaleString()} in-stock units
          </Text>
          <Progress.Root size={8} radius="xl">
            {agingBands.map((band, index) => (
              <Progress.Section
                key={band.label}
                value={(band.in_stock_units / total) * 100}
                color={`gray.${AGING_SHADES[index] ?? 6}`}
              />
            ))}
          </Progress.Root>
          <div className={classes.agingLegend}>
            {agingBands.map((band, index) => (
              <div className={classes.agingRow} key={band.label}>
                <Group gap="xs" wrap="nowrap">
                  <ColorSwatch
                    size={8}
                    color={`var(--mantine-color-gray-${AGING_SHADES[index] ?? 6})`}
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

function TopSetsList({ topSets }: { topSets: ReportTopSet[] }) {
  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle
        title="Largest sets in stock"
        subtitle="Ranked by card count"
      />
      {topSets.length === 0 ? (
        <Text size="sm" c="dimmed">
          No sets with cards in stock.
        </Text>
      ) : (
        <ol
          className={classes.topSetsList}
          aria-label="Sets ranked by in-stock card count"
        >
          {topSets.map((set, index) => (
            <li className={classes.topSetRow} key={set.set_code}>
              <Text size="xs" c="dimmed" className={classes.topSetRank}>
                {index + 1}
              </Text>
              <div className={classes.topSetIdentity}>
                <Text size="sm" fw={500}>
                  {set.set_name}
                </Text>
                <Text size="xs" c="dimmed" tt="uppercase">
                  {set.set_code}
                </Text>
              </div>
              <Text size="sm" className={classes.topSetCount}>
                {set.in_stock_units.toLocaleString()}
              </Text>
            </li>
          ))}
        </ol>
      )}
    </Paper>
  );
}

function PriceBucketsChart({
  priceBuckets,
}: {
  priceBuckets: ReportPriceBucket[];
}) {
  const highestCount = Math.max(
    ...priceBuckets.map((bucket) => bucket.in_stock_units),
    0,
  );

  return (
    <Paper p="md" radius="md" withBorder>
      <FigureTitle
        title="Stock by price"
        subtitle="In-stock units · unit price in NZD"
      />
      {highestCount === 0 ? (
        <Text size="sm" c="dimmed">
          No in-stock cards have a price.
        </Text>
      ) : (
        <Stack gap="sm" className={classes.distributionList}>
          {priceBuckets.map((bucket) => (
            <div className={classes.distributionRow} key={bucket.label}>
              <Text size="sm">{bucket.label}</Text>
              <div className={classes.distributionTrack} aria-hidden="true">
                <div
                  className={classes.distributionFill}
                  style={{
                    width: `${(bucket.in_stock_units / highestCount) * 100}%`,
                  }}
                />
              </div>
              <Text size="sm" className={classes.distributionCount}>
                {bucket.in_stock_units.toLocaleString()}
              </Text>
            </div>
          ))}
        </Stack>
      )}
    </Paper>
  );
}

function GameSummary({ gameReport }: { gameReport: ReportGame }) {
  const { totals } = gameReport;

  return (
    <Paper
      component="section"
      aria-label={`${gameLabel(gameReport.game)} inventory summary`}
      withBorder
      radius="md"
      p={0}
      className={classes.gameSummary}
    >
      <div
        className={classes.gameSummaryGroup}
        role="group"
        aria-label="Inventory value"
      >
        <Text size="sm" c="dimmed" fw={600}>
          Inventory value
        </Text>
        <Text className={classes.gameSummaryValue}>
          {formatCurrency(totals.inventory_value)}
        </Text>
      </div>
      <div
        className={classes.gameSummaryGroup}
        role="group"
        aria-label="Unique card names"
      >
        <Text size="sm" c="dimmed" fw={600}>
          Unique card names
        </Text>
        <Text className={classes.gameSummaryValue}>
          {gameReport.unique_card_names.toLocaleString()}
        </Text>
        <Text size="xs" c="dimmed" className={classes.gameSummarySecondary}>
          {totals.in_stock_units.toLocaleString()} units in stock
        </Text>
      </div>
      <div
        className={classes.gameSummaryGroup}
        role="group"
        aria-label="Paid revenue"
      >
        <Text size="sm" c="dimmed" fw={600}>
          Paid revenue
        </Text>
        <Text className={classes.gameSummaryValue}>
          {formatCurrency(totals.revenue_to_date)}
        </Text>
        <Text size="xs" c="dimmed" className={classes.gameSummarySecondary}>
          {totals.sold_units.toLocaleString()} units sold
        </Text>
      </div>
    </Paper>
  );
}

function GameBreakdown({ gameReport }: { gameReport: ReportGame }) {
  return (
    <Stack gap="md" aria-label={`${gameLabel(gameReport.game)} report`}>
      <GameSummary gameReport={gameReport} />
      <SimpleGrid cols={{ base: 1, xl: 2 }} spacing="md">
        <TopHitsTable topHits={gameReport.top_hits} />
        <TopSetsList topSets={gameReport.top_sets} />
      </SimpleGrid>
      <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
        <StockAgingFigure agingBands={gameReport.aging_bands} />
        <PriceBucketsChart priceBuckets={gameReport.price_buckets} />
      </SimpleGrid>
    </Stack>
  );
}

export function ReportsPage() {
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [activeGame, setActiveGame] = useState<GameId>(GAMES[0].id);
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
              : 'Inventory value, sales, and the cards currently in stock.'
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

        {!firstVisit && report?.report.totals && (
          <>
            <TotalsStrip totals={report.report.totals} />

            <SimpleGrid cols={{ base: 1, md: 2 }} spacing="md">
              <IntakeVsSalesChart
                intakeVsSales={report.report.intake_vs_sales_by_week}
              />
              <RevenueByMonthChart
                revenueByMonth={report.report.revenue_by_month}
              />
            </SimpleGrid>

            <Tabs
              className={classes.gameTabs}
              value={activeGame}
              onChange={(value) => {
                if (value) {
                  setActiveGame(value as GameId);
                }
              }}
            >
              <Tabs.List
                className={classes.gameTabsList}
                aria-label="Report game"
              >
                {GAMES.map((game) => (
                  <Tabs.Tab key={game.id} value={game.id}>
                    {game.label}
                  </Tabs.Tab>
                ))}
              </Tabs.List>
              {GAMES.map((game) => {
                const gameReport = report.report.games.find(
                  (entry) => entry.game === game.id,
                );
                if (!gameReport) {
                  throw new Error(`Report is missing ${game.id} breakdown`);
                }
                return (
                  <Tabs.Panel key={game.id} value={game.id} pt="md">
                    <GameBreakdown gameReport={gameReport} />
                  </Tabs.Panel>
                );
              })}
            </Tabs>
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
