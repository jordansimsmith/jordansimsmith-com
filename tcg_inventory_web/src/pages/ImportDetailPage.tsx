import { useEffect, useRef, useState } from 'react';
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
import { notifications } from '@mantine/notifications';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { PageHeader } from '../components/PageHeader';
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
import classes from './ImportDetailPage.module.css';

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
      <Stack gap="lg">
        {loading && (
          <>
            <Stack gap="xs">
              <Skeleton height={29} width={280} />
              <Skeleton height={18} width={220} />
            </Stack>
            <Paper withBorder radius="md" p="md">
              <Skeleton height={24} width={140} mb="md" />
              <Skeleton height={24} width="100%" maw={320} />
            </Paper>
            <Paper withBorder radius="md" p="md">
              <Skeleton height={24} width={140} mb="md" />
              <Stack gap="xs">
                <Skeleton height={36} />
                <Skeleton height={36} />
                <Skeleton height={36} />
              </Stack>
            </Paper>
          </>
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
            <PageHeader
              title={importDetail.filename}
              description={`Uploaded ${new Date(importDetail.created_at * 1000).toLocaleString()}`}
              actions={
                <Button variant="subtle" onClick={() => navigate('/imports')}>
                  Back to imports
                </Button>
              }
            />
            {importDetail.appraisal_error && (
              <JobFailureAlert
                title="Appraisal failed"
                error={importDetail.appraisal_error}
                maw={480}
              />
            )}
            <Paper
              component="section"
              aria-label="Import summary"
              withBorder
              radius="md"
              p="md"
            >
              <Stack gap="md">
                <Group justify="space-between" gap="sm">
                  <Group gap="sm">
                    <Text fw={600} size="sm">
                      Import summary
                    </Text>
                    <ImportStatusBadge importSummary={importDetail} />
                  </Group>
                  {importDetail.status === 'appraising' && (
                    <Text size="sm" c="dimmed" className={classes.numeric}>
                      {importDetail.row_count} rows
                    </Text>
                  )}
                </Group>
                {!importDetail.appraisal_error &&
                  importDetail.status === 'appraising' && (
                    <Stack gap="xs">
                      <Text size="sm" className={classes.numeric}>
                        Appraising {appraised} of {importDetail.row_count}
                      </Text>
                      <Progress
                        value={(appraised / importDetail.row_count) * 100}
                        animated
                      />
                    </Stack>
                  )}
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
                  {importDetail.status === 'confirmed' &&
                    confirmResult === null && (
                      <Text size="sm" c="dimmed" className={classes.numeric}>
                        {`Total suggested value $${importDetail.total_suggested_price}`}
                      </Text>
                    )}
                </Group>
                {importDetail.status === 'review' && needsPhotosCount > 0 && (
                  <Text size="sm" c="orange.8">
                    {needsPhotosCount === 1
                      ? '1 row needs photos before confirm'
                      : `${needsPhotosCount} rows need photos before confirm`}
                  </Text>
                )}
              </Stack>
            </Paper>
            {showReview && (
              <CollectionSurface
                ariaLabel="Import rows"
                toolbar={
                  <Group justify="space-between" gap="sm">
                    <Text size="sm" fw={600}>
                      Import rows
                    </Text>
                    <Text size="sm" c="dimmed" className={classes.numeric}>
                      {rows.length} {rows.length === 1 ? 'row' : 'rows'}
                    </Text>
                  </Group>
                }
                footer={
                  importDetail.status === 'review' ? (
                    <Group
                      className={classes.actions}
                      justify="space-between"
                      gap="sm"
                    >
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
                  ) : undefined
                }
              >
                {rows.length > 0 ? (
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
                ) : (
                  <CollectionMessage title="No rows in this import" />
                )}
              </CollectionSurface>
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
