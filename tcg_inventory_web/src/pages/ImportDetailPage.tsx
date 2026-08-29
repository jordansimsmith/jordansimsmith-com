import { useEffect, useRef, useState } from 'react';
import {
  Badge,
  Button,
  Group,
  Progress,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { ImportStatusBadge } from '../components/ImportStatusBadge';
import { JobFailureAlert } from '../components/JobFailureAlert';
import { ImportReviewTable } from '../components/ImportReviewTable';
import { ConfirmImportModal } from '../components/ConfirmImportModal';
import { DeleteImportModal } from '../components/DeleteImportModal';
import { PlacementInstructionsView } from '../components/PlacementInstructionsView';
import { apiClient } from '../api/client';
import type {
  Condition,
  ConfirmImportResponse,
  ImportDetail,
} from '../api/client';
import { encodeListingPhoto } from '../domain/encode-listing-photo';
import { useListNavigation } from '../hooks/use-list-navigation';

const POLL_INTERVAL_MS = 2000;

export function ImportDetailPage() {
  const { importId } = useParams<{ importId: string }>();
  const navigate = useNavigate();
  const [importDetail, setImportDetail] = useState<ImportDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [confirmResult, setConfirmResult] =
    useState<ConfirmImportResponse | null>(null);
  const [deleteOpen, setDeleteOpen] = useState(false);
  const [deleting, setDeleting] = useState(false);
  // this page has no search input; the ref keeps the navigation hook inert on "/"
  const searchInputRef = useRef<HTMLInputElement>(null);
  const importDetailRef = useRef(importDetail);
  importDetailRef.current = importDetail;

  const rows = importDetail?.rows ?? [];
  const showReview =
    importDetail !== null &&
    importDetail.status !== 'appraising' &&
    !importDetail.appraisal_error &&
    confirmResult === null;

  const { selectedIndex, setSelectedIndex } = useListNavigation({
    itemCount: showReview ? rows.length : 0,
    onOpen: () => {},
    searchInputRef,
  });

  useEffect(() => {
    if (!importId) {
      return;
    }
    let cancelled = false;
    let timer: ReturnType<typeof setTimeout> | undefined;

    const poll = async () => {
      try {
        const response = await apiClient.getImport(importId);
        if (cancelled) {
          return;
        }
        setImportDetail(response);
        setError(null);
        if (response.status === 'appraising' && !response.appraisal_error) {
          timer = setTimeout(poll, POLL_INTERVAL_MS);
        }
      } catch (e) {
        if (cancelled) {
          return;
        }
        setError(e instanceof Error ? e.message : 'Failed to load import');
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
  }, [importId]);

  useEffect(() => {
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape' || confirmOpen || deleteOpen) {
        return;
      }
      const target = event.target;
      if (
        target instanceof HTMLInputElement ||
        target instanceof HTMLTextAreaElement
      ) {
        return;
      }
      navigate('/imports');
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [confirmOpen, deleteOpen, navigate]);

  useEffect(() => {
    const handleVisibilityChange = async () => {
      if (document.visibilityState !== 'visible' || !importId) {
        return;
      }
      if (importDetailRef.current?.status !== 'review') {
        return;
      }
      try {
        const response = await apiClient.getImport(importId);
        setImportDetail(response);
      } catch (e) {
        const message =
          e instanceof Error ? e.message : 'Failed to load import';
        notifications.show({ title: 'Error', message, color: 'red' });
      }
    };
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => {
      document.removeEventListener('visibilitychange', handleVisibilityChange);
    };
  }, [importId]);

  const handleConditionChange = async (
    position: number,
    condition: Condition,
  ) => {
    if (!importId) {
      return;
    }
    try {
      const updated = await apiClient.updateImportRow(
        importId,
        position,
        condition,
      );
      setImportDetail((current) => {
        if (!current) {
          return current;
        }
        return {
          ...current,
          rows: current.rows.map((row) =>
            row.position === position ? { ...row, ...updated } : row,
          ),
        };
      });
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to update condition';
      notifications.show({ title: 'Error', message, color: 'red' });
    }
  };

  const handleDeleteRow = async (position: number) => {
    if (!importId) {
      return;
    }
    try {
      await apiClient.deleteImportRow(importId, position);
      setImportDetail((current) => {
        if (!current) {
          return current;
        }
        return {
          ...current,
          rows: current.rows.filter((row) => row.position !== position),
        };
      });
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to delete row';
      notifications.show({ title: 'Error', message, color: 'red' });
    }
  };

  const handleAddPhoto = async (position: number, file: File) => {
    if (!importId) {
      return;
    }
    try {
      const jpeg = await encodeListingPhoto(file);
      await apiClient.addRowPhoto(importId, position, jpeg);
      const updated = await apiClient.getImport(importId);
      setImportDetail(updated);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to add photo';
      notifications.show({ title: 'Error', message, color: 'red' });
    }
  };

  const handleRemovePhoto = async (position: number, photoId: string) => {
    if (!importId) {
      return;
    }
    try {
      await apiClient.deleteRowPhoto(importId, position, photoId);
      const updated = await apiClient.getImport(importId);
      setImportDetail(updated);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to remove photo';
      notifications.show({ title: 'Error', message, color: 'red' });
    }
  };

  const handleConfirm = async () => {
    if (!importId) {
      return;
    }
    setConfirming(true);
    try {
      const response = await apiClient.confirmImport(importId);
      setConfirmResult(response);
      setConfirmOpen(false);
      setImportDetail((current) =>
        current ? { ...current, status: response.status } : current,
      );
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to confirm import';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setConfirming(false);
    }
  };

  const handleDelete = async () => {
    if (!importId) {
      return;
    }
    setDeleting(true);
    try {
      await apiClient.deleteImport(importId);
      navigate('/imports');
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to delete import';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setDeleting(false);
    }
  };

  const keepCount = rows.filter((r) => r.decision === 'keep').length;
  const discardCount = rows.filter((r) => r.decision === 'discard').length;
  const reviewCount = rows.filter((r) => r.decision === 'review').length;
  const needsPhotosCount = rows.filter((r) => r.needs_photos).length;
  const appraised = keepCount + discardCount + reviewCount;

  return (
    <AppShellLayout>
      <Stack gap="md">
        {loading && (
          <Stack gap="sm">
            <Skeleton height={32} width={280} />
            <Skeleton height={20} width={200} />
            <Skeleton height={20} width={240} />
          </Stack>
        )}
        {!loading && error && (
          <Stack align="flex-start" gap="md">
            <Text c="red">{error}</Text>
            <Button variant="default" onClick={() => navigate('/imports')}>
              Back to imports
            </Button>
          </Stack>
        )}
        {!loading && !error && importDetail && (
          <>
            <Stack gap="xs">
              <Title order={2}>{importDetail.filename}</Title>
              <Group gap="sm">
                <ImportStatusBadge importSummary={importDetail} />
                <Text size="sm" c="dimmed">
                  Uploaded{' '}
                  {new Date(importDetail.created_at * 1000).toLocaleString()}
                </Text>
              </Group>
            </Stack>
            {importDetail.appraisal_error && (
              <JobFailureAlert
                title="Appraisal failed"
                error={importDetail.appraisal_error}
                maw={480}
              />
            )}
            {!importDetail.appraisal_error &&
              importDetail.status === 'appraising' && (
                <Stack gap="xs" maw={480}>
                  <Text size="sm">
                    Appraising {appraised} of {importDetail.row_count}
                  </Text>
                  <Progress
                    value={(appraised / importDetail.row_count) * 100}
                    animated
                  />
                </Stack>
              )}
            {confirmResult === null && (
              <Group justify="space-between" align="center">
                <Group gap="sm">
                  <Badge variant="light" color="green">
                    Keep {keepCount}
                  </Badge>
                  <Badge variant="light" color="gray">
                    Discard {discardCount}
                  </Badge>
                  <Badge variant="light" color="yellow">
                    Review {reviewCount}
                  </Badge>
                  {importDetail.status === 'review' && needsPhotosCount > 0 && (
                    <Text size="sm" c="dimmed">
                      {needsPhotosCount === 1
                        ? '1 row needs photos before confirm'
                        : `${needsPhotosCount} rows need photos before confirm`}
                    </Text>
                  )}
                </Group>
                {importDetail.status === 'review' && (
                  <Group gap="sm">
                    <Button
                      variant="outline"
                      color="red"
                      onClick={() => setDeleteOpen(true)}
                    >
                      Delete import
                    </Button>
                    <Button
                      onClick={() => setConfirmOpen(true)}
                      disabled={needsPhotosCount > 0}
                    >
                      Confirm import
                    </Button>
                  </Group>
                )}
              </Group>
            )}
            {showReview && rows.length > 0 && (
              <ImportReviewTable
                rows={rows}
                selectedIndex={selectedIndex}
                onSelect={setSelectedIndex}
                editable={importDetail.status === 'review'}
                onConditionChange={handleConditionChange}
                onDeleteRow={handleDeleteRow}
                onAddPhoto={handleAddPhoto}
                onRemovePhoto={handleRemovePhoto}
              />
            )}
            {confirmResult && (
              <PlacementInstructionsView
                result={confirmResult}
                onDone={() => navigate('/imports')}
              />
            )}
            <ConfirmImportModal
              opened={confirmOpen}
              keepCount={keepCount}
              discardCount={discardCount}
              reviewCount={reviewCount}
              loading={confirming}
              onCancel={() => setConfirmOpen(false)}
              onConfirm={handleConfirm}
            />
            <DeleteImportModal
              opened={deleteOpen}
              filename={importDetail.filename}
              rowCount={importDetail.row_count}
              loading={deleting}
              onCancel={() => setDeleteOpen(false)}
              onConfirm={handleDelete}
            />
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
