import { useCallback, useEffect, useRef, useState } from 'react';
import { Group, Loader, Skeleton, Stack, Text, Title } from '@mantine/core';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { apiClient } from '../api/client';
import type { ReportResponse } from '../api/client';

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

        {!firstVisit && report && Object.keys(report.report).length === 0 && (
          <Text c="dimmed">No report data yet.</Text>
        )}
      </Stack>
    </AppShellLayout>
  );
}
