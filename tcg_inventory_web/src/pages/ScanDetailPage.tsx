import { useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Paper,
  Progress,
  Skeleton,
  Stack,
  Text,
} from '@mantine/core';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { PageHeader } from '../components/PageHeader';
import { ScanReview } from '../components/ScanReview';
import { apiClient } from '../api/client';
import type { ScanDetail, ScanStatus } from '../api/client';

const POLL_INTERVAL_MS = 2000;

const STATUS_COLORS: Record<ScanStatus, string> = {
  uploading: 'blue',
  identifying: 'blue',
  reviewing: 'orange',
  confirmed: 'green',
};

function formatStatus(status: ScanStatus): string {
  return status.replace('_', ' ');
}

function formatFinish(finish: ScanDetail['finish']): string {
  switch (finish) {
    case 'normal':
      return 'Normal';
    case 'foil':
      return 'Foil';
    case 'etched':
      return 'Etched';
  }
}

function ScanSummary({ scan }: { scan: ScanDetail }) {
  const progress =
    scan.row_count === 0 ? 0 : (scan.processed_count / scan.row_count) * 100;

  return (
    <Paper
      component="section"
      aria-label="Scan summary"
      withBorder
      radius="md"
      p="md"
    >
      <Stack gap="md">
        <Group justify="space-between" gap="sm" wrap="wrap">
          <Group gap="sm">
            <Text fw={600} size="sm">
              Scan summary
            </Text>
            <Badge variant="light" color={STATUS_COLORS[scan.status]}>
              {formatStatus(scan.status)}
            </Badge>
          </Group>
          <Text size="sm" c="dimmed">
            {scan.row_count} {scan.row_count === 1 ? 'card' : 'cards'}
          </Text>
        </Group>
        <Group gap="sm">
          <Badge variant="light">{scan.condition}</Badge>
          <Badge variant="light">{formatFinish(scan.finish)}</Badge>
        </Group>
        {scan.status === 'identifying' && (
          <Stack gap="xs">
            <Text size="sm">
              Identifying {scan.processed_count} of {scan.row_count}
            </Text>
            <Progress
              value={progress}
              animated
              aria-label="Identification progress"
            />
          </Stack>
        )}
        {scan.status === 'uploading' && (
          <Text size="sm" c="dimmed">
            This upload is incomplete and cannot be resumed.
          </Text>
        )}
        {scan.status === 'reviewing' && (
          <Text size="sm" c="dimmed">
            Identification is complete. This scan is ready for review.
          </Text>
        )}
        {scan.status === 'confirmed' && (
          <Text size="sm" c="dimmed">
            This scan is confirmed and read-only.
          </Text>
        )}
        {scan.error && (
          <Text size="sm" c="red.7" role="alert">
            {scan.error}
          </Text>
        )}
      </Stack>
    </Paper>
  );
}

export function ScanDetailPage() {
  const { scanId } = useParams<{ scanId: string }>();
  const navigate = useNavigate();
  const [scan, setScan] = useState<ScanDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!scanId) {
      return;
    }

    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        const response = await apiClient.getScan(scanId);
        if (cancelled) {
          return;
        }
        setScan(response);
        setError(null);
        if (response.status === 'identifying') {
          timer = setTimeout(poll, POLL_INTERVAL_MS);
        }
      } catch (e) {
        if (!cancelled) {
          setError(e instanceof Error ? e.message : 'Failed to load scan');
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    poll();
    return () => {
      cancelled = true;
      clearTimeout(timer);
    };
  }, [scanId]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLElement &&
        target.closest('[data-scan-review]')
      ) {
        return;
      }
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement
      ) {
        return;
      }
      navigate('/scans');
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [navigate]);

  return (
    <AppShellLayout>
      <Stack gap="lg">
        {loading && (
          <Stack gap="md" aria-label="Loading scan">
            <Skeleton height={36} width="40%" />
            <Skeleton height={180} />
          </Stack>
        )}
        {!loading && error && (
          <CollectionSurface>
            <CollectionMessage
              title="Scan could not be loaded"
              description={error}
              tone="error"
            />
          </CollectionSurface>
        )}
        {!loading && !error && scan && (
          <>
            <PageHeader
              title="Scan"
              description={`Created ${new Date(scan.created_at * 1000).toLocaleString()}`}
              actions={
                <Button variant="subtle" onClick={() => navigate('/scans')}>
                  Back to scans
                </Button>
              }
            />
            {scan.status === 'reviewing' ? (
              <ScanReview scan={scan} />
            ) : (
              <ScanSummary scan={scan} />
            )}
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
