import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Group,
  Loader,
  Paper,
  SimpleGrid,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { BarChart } from '@mantine/charts';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type {
  ReportPriceBucket,
  ReportResponse,
  ReportTopSet,
  ReportTotals,
} from '../api/client';

dayjs.extend(relativeTime);

const POLL_INTERVAL_MS = 2000;
const TWENTY_FOUR_HOURS_S = 24 * 60 * 60;

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

function formatCurrency(value: string): string {
  return `$${value}`;
}

function StatCard({ label, value }: { label: string; value: string }) {
  return (
    <Paper p="md" radius="sm" withBorder>
      <Text size="xs" c="dimmed" tt="uppercase" fw={700}>
        {label}
      </Text>
      <Text size="xl" fw={700} mt={4}>
        {value}
      </Text>
    </Paper>
  );
}

function TotalsStrip({ totals }: { totals: ReportTotals }) {
  return (
    <SimpleGrid cols={{ base: 2, sm: 3, md: 6 }} spacing="md">
      <StatCard
        label="Inventory Value"
        value={formatCurrency(totals.inventory_value)}
      />
      <StatCard
        label="In Stock"
        value={totals.in_stock_units.toLocaleString()}
      />
      <StatCard label="SKUs" value={totals.sku_count.toLocaleString()} />
      <StatCard
        label="Reserved"
        value={totals.reserved_units.toLocaleString()}
      />
      <StatCard label="Sold" value={totals.sold_units.toLocaleString()} />
      <StatCard
        label="Revenue"
        value={formatCurrency(totals.revenue_to_date)}
      />
      {totals.unpriced_units > 0 && (
        <StatCard
          label="Unpriced"
          value={totals.unpriced_units.toLocaleString()}
        />
      )}
    </SimpleGrid>
  );
}

function TopSetsChart({ topSets }: { topSets: ReportTopSet[] }) {
  const data = topSets.map((s) => ({
    set_name: s.set_name,
    in_stock_units: s.in_stock_units,
  }));

  return (
    <Paper p="md" radius="sm" withBorder>
      <Text size="sm" fw={700} mb="md">
        Top sets by in-stock units
      </Text>
      <BarChart
        h={300}
        data={data}
        dataKey="set_name"
        series={[
          { name: 'in_stock_units', label: 'In stock', color: 'blue.6' },
        ]}
        orientation="vertical"
        gridAxis="x"
        tickLine="x"
        yAxisProps={{ width: 160 }}
      />
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
      <Text size="sm" fw={700} mb="md">
        Price distribution
      </Text>
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
            {refreshing && <Loader size="xs" aria-label="Refreshing" />}
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
          <TotalsStrip totals={report.report.totals} />
        )}

        {!firstVisit &&
          report?.report?.top_sets &&
          report.report.top_sets.length > 0 && (
            <TopSetsChart topSets={report.report.top_sets} />
          )}

        {!firstVisit &&
          report?.report?.price_buckets &&
          report.report.price_buckets.length > 0 && (
            <PriceBucketsChart priceBuckets={report.report.price_buckets} />
          )}

        {!firstVisit && report && !report.report?.totals && (
          <Text c="dimmed">No report data yet.</Text>
        )}
      </Stack>
    </AppShellLayout>
  );
}
