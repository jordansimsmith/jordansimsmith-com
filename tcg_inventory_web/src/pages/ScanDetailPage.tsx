import { useEffect, useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Modal,
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
import type {
  ScanConfirmationRow,
  ScanDetail,
  ScanStatus,
} from '../api/client';

const POLL_INTERVAL_MS = 2000;

const STATUS_COLORS: Record<ScanStatus, string> = {
  uploading: 'blue',
  identifying: 'blue',
  reviewing: 'yellow',
  confirmed: 'green',
};

function formatStatus(status: ScanStatus): string {
  if (status === 'reviewing') {
    return 'review';
  }
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
  const processedCount = scan.rows.filter((row) => row.status !== null).length;
  const progress =
    scan.row_count === 0 ? 0 : (processedCount / scan.row_count) * 100;

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
              Identifying {processedCount} of {scan.row_count}
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
  const [deleteScanOpen, setDeleteScanOpen] = useState(false);
  const [deleteScanLoading, setDeleteScanLoading] = useState(false);
  const [deleteScanError, setDeleteScanError] = useState<string | null>(null);
  const [reviewMutationLoading, setReviewMutationLoading] = useState(false);

  const deleteScan = async () => {
    if (!scanId || deleteScanLoading || reviewMutationLoading) {
      return;
    }
    setDeleteScanLoading(true);
    setDeleteScanError(null);
    try {
      await apiClient.deleteScan(scanId);
      navigate('/scans');
    } catch (e) {
      setDeleteScanError(
        e instanceof Error ? e.message : 'Scan deletion failed',
      );
    } finally {
      setDeleteScanLoading(false);
    }
  };

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
      if (event.key !== 'Escape' || deleteScanOpen || reviewMutationLoading) {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLElement &&
        target.closest('[data-scan-review-editable]')
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
  }, [deleteScanOpen, navigate, reviewMutationLoading]);

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
                <Group gap="xs">
                  {scan.status !== 'confirmed' && (
                    <Button
                      color="red"
                      variant="subtle"
                      disabled={reviewMutationLoading || deleteScanLoading}
                      onClick={() => {
                        setDeleteScanError(null);
                        setDeleteScanOpen(true);
                      }}
                    >
                      Delete scan
                    </Button>
                  )}
                  {scan.status === 'confirmed' && scan.import_id && (
                    <Button
                      variant="light"
                      onClick={() => navigate(`/imports/${scan.import_id}`)}
                    >
                      Open import
                    </Button>
                  )}
                  <Button
                    variant="subtle"
                    disabled={reviewMutationLoading || deleteScanLoading}
                    onClick={() => navigate('/scans')}
                  >
                    Back to scans
                  </Button>
                </Group>
              }
            />
            {scan.status === 'reviewing' ? (
              <ScanReview
                scan={scan}
                onDeleteRow={async (scanPosition) => {
                  setReviewMutationLoading(true);
                  try {
                    await apiClient.deleteScanRow(scan.scan_id, scanPosition);
                    const refreshed = await apiClient.getScan(scan.scan_id);
                    setScan(refreshed);
                  } finally {
                    setReviewMutationLoading(false);
                  }
                }}
                onConfirmScan={async (rows: ScanConfirmationRow[]) => {
                  setReviewMutationLoading(true);
                  try {
                    const response = await apiClient.confirmScan(scan.scan_id, {
                      rows,
                    });
                    navigate(`/imports/${response.import_id}`);
                  } finally {
                    setReviewMutationLoading(false);
                  }
                }}
              />
            ) : (
              <ScanSummary scan={scan} />
            )}
          </>
        )}
        <Modal
          opened={deleteScanOpen}
          onClose={() => {
            if (!deleteScanLoading && !reviewMutationLoading) {
              setDeleteScanOpen(false);
              setDeleteScanError(null);
            }
          }}
          title="Delete scan"
          centered
        >
          <Text mb="md">
            Delete this unfinished scan and its {scan?.row_count ?? 0}{' '}
            {scan?.row_count === 1 ? 'source card' : 'source cards'}? This
            cannot be undone.
          </Text>
          {deleteScanError && (
            <Text c="red.7" size="sm" role="alert" mb="md">
              {deleteScanError}
            </Text>
          )}
          <Group justify="flex-end">
            <Button
              variant="default"
              disabled={deleteScanLoading || reviewMutationLoading}
              onClick={() => setDeleteScanOpen(false)}
            >
              Cancel
            </Button>
            <Button
              color="red"
              loading={deleteScanLoading}
              disabled={reviewMutationLoading}
              onClick={() => void deleteScan()}
            >
              Delete scan
            </Button>
          </Group>
        </Modal>
      </Stack>
    </AppShellLayout>
  );
}
