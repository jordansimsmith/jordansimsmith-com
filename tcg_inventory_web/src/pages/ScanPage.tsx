import { useCallback, useEffect, useMemo, useState } from 'react';
import {
  Badge,
  Button,
  FileInput,
  Group,
  Select,
  Stack,
  Table,
  Text,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionLoadingState,
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { PageHeader } from '../components/PageHeader';
import { apiClient, CONDITIONS } from '../api/client';
import { scanUploader } from '../api/scan-uploader';
import type { Condition, Finish, ScanStatus, ScanSummary } from '../api/client';
import { sortScanFiles, validateScanFiles } from '../domain/scan-files';
import { GAMES, gameLabel, getGame } from '../domain/games';
import type { GameId } from '../domain/games';
import classes from '../components/CollectionTable.module.css';
import formClasses from './ScanPage.module.css';

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

function formatFinish(finish: ScanSummary['finish']): string {
  switch (finish) {
    case 'normal':
      return 'Normal';
    case 'foil':
      return 'Foil';
    case 'etched':
      return 'Etched';
  }
}

function ScanStatusBadge({ status }: { status: ScanStatus }) {
  return (
    <Badge variant="light" color={STATUS_COLORS[status]}>
      {formatStatus(status)}
    </Badge>
  );
}

interface ScanTableProps {
  scans: ScanSummary[];
  onOpen: (scan: ScanSummary) => void;
}

function ScanTable({ scans, onOpen }: ScanTableProps) {
  return (
    <Table
      highlightOnHover
      verticalSpacing={4}
      horizontalSpacing="sm"
      fz="sm"
      className={classes.table}
    >
      <Table.Thead>
        <Table.Tr>
          <Table.Th>Created</Table.Th>
          <Table.Th>Game</Table.Th>
          <Table.Th>Status</Table.Th>
          <Table.Th ta="right">Cards</Table.Th>
          <Table.Th>Condition</Table.Th>
          <Table.Th>Finish</Table.Th>
        </Table.Tr>
      </Table.Thead>
      <Table.Tbody>
        {scans.map((scan) => (
          <Table.Tr
            key={scan.scan_id}
            onClick={() => onOpen(scan)}
            style={{ cursor: 'pointer' }}
          >
            <Table.Td
              fw={500}
              data-field="created"
              data-label="Created"
              style={{ whiteSpace: 'nowrap' }}
            >
              {new Date(scan.created_at * 1000).toLocaleString()}
            </Table.Td>
            <Table.Td data-field="game" data-label="Game">
              {gameLabel(scan.game)}
            </Table.Td>
            <Table.Td data-field="status" data-label="Status">
              <ScanStatusBadge status={scan.status} />
            </Table.Td>
            <Table.Td ta="right" data-field="cards" data-label="Cards">
              {scan.row_count}
            </Table.Td>
            <Table.Td data-field="condition" data-label="Condition">
              {scan.condition}
            </Table.Td>
            <Table.Td data-field="finish" data-label="Finish">
              {formatFinish(scan.finish)}
            </Table.Td>
          </Table.Tr>
        ))}
      </Table.Tbody>
    </Table>
  );
}

export function ScanPage() {
  const navigate = useNavigate();
  const [scans, setScans] = useState<ScanSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [game, setGame] = useState<GameId | null>(null);
  const [condition, setCondition] = useState<Condition>('NM');
  const [finish, setFinish] = useState<Finish | null>(null);
  const [files, setFiles] = useState<File[]>([]);
  const [creating, setCreating] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  const sortedFiles = useMemo(() => sortScanFiles(files), [files]);
  const fileErrors = useMemo(() => validateScanFiles(files), [files]);
  const openScan = useCallback(
    (scan: ScanSummary) => {
      navigate(`/scans/${encodeURIComponent(scan.scan_id)}`);
    },
    [navigate],
  );
  const refreshScans = useCallback(async () => {
    const response = await apiClient.findScans();
    setScans(response.scans);
    setNextContinuation(response.next_continuation);
    setError(null);
  }, []);

  useEffect(() => {
    let cancelled = false;
    const fetchScans = async () => {
      try {
        const response = await apiClient.findScans();
        if (!cancelled) {
          setScans(response.scans);
          setNextContinuation(response.next_continuation);
          setError(null);
        }
      } catch (e) {
        if (!cancelled) {
          const message =
            e instanceof Error ? e.message : 'Failed to load scans';
          setError(message);
          notifications.show({ title: 'Error', message, color: 'red' });
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchScans();
    return () => {
      cancelled = true;
    };
  }, []);

  const handleLoadMore = async () => {
    if (!nextContinuation) {
      return;
    }
    setLoadingMore(true);
    try {
      const response = await apiClient.findScans({
        continuation: nextContinuation,
      });
      setScans((previous) => [...previous, ...response.scans]);
      setNextContinuation(response.next_continuation);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to load more scans';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setLoadingMore(false);
    }
  };

  const handleFilesChange = (nextFiles: File[] | null) => {
    setFiles(nextFiles ?? []);
    setSubmitError(null);
  };

  const handleCreate = async () => {
    if (
      creating ||
      game === null ||
      finish === null ||
      sortedFiles.length === 0 ||
      fileErrors.length > 0
    ) {
      return;
    }

    setCreating(true);
    setSubmitError(null);
    try {
      const created = await apiClient.createScan({
        game,
        condition,
        finish,
        files: sortedFiles.map((file) => ({
          filename: file.name,
          size_bytes: file.size,
        })),
      });

      await refreshScans();

      const filesByName = new Map(sortedFiles.map((file) => [file.name, file]));
      const uploads = created.rows.map((slot) => {
        const file = filesByName.get(slot.filename);
        if (!file || !slot.upload_url) {
          throw new Error(`Missing upload slot for ${slot.filename}`);
        }
        return { slot, file };
      });

      await scanUploader.uploadBatch(created.scan_id, uploads);
      const verified = await apiClient.getScan(created.scan_id);
      if (
        verified.rows.length !== sortedFiles.length ||
        verified.rows.some((row) => !row.uploaded)
      ) {
        throw new Error('Upload verification found missing files');
      }

      await apiClient.identifyScan(created.scan_id);
      setFiles([]);
      navigate(`/scans/${encodeURIComponent(created.scan_id)}`);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'The scan batch could not be created';
      setSubmitError(message);
      notifications.show({
        title: 'Scan creation failed',
        message,
        color: 'red',
      });
    } finally {
      setCreating(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Scans"
          description="Upload card-front JPEGs to identify a physical stack."
        />
        <CollectionSurface
          ariaLabel="Scan jobs"
          toolbar={
            <Stack gap="sm">
              <div className={formClasses.createScanForm}>
                <Select
                  className={formClasses.game}
                  label="Game"
                  value={game}
                  onChange={(value) => {
                    if (!value) {
                      setGame(null);
                      setFinish(null);
                      return;
                    }
                    const nextGame = getGame(value);
                    setGame(nextGame.id);
                    setFinish((currentFinish) =>
                      currentFinish && nextGame.finishes.includes(currentFinish)
                        ? currentFinish
                        : (nextGame.finishes[0] ?? null),
                    );
                  }}
                  data={GAMES.map(({ id, label }) => ({ value: id, label }))}
                  placeholder="Select game"
                  required
                  disabled={creating}
                />
                <Select
                  className={formClasses.condition}
                  label="Condition"
                  value={condition}
                  onChange={(value) => {
                    if (value) {
                      setCondition(value as Condition);
                    }
                  }}
                  data={CONDITIONS}
                  disabled={creating}
                />
                <Select
                  className={formClasses.finish}
                  label="Finish"
                  value={finish}
                  onChange={(value) => {
                    if (value) {
                      setFinish(value as Finish);
                    }
                  }}
                  data={
                    game === null
                      ? []
                      : [...getGame(game).finishes].map((value) => ({
                          value,
                          label: formatFinish(value),
                        }))
                  }
                  placeholder={
                    game === null ? 'Select game first' : 'Select finish'
                  }
                  disabled={creating || game === null}
                  required
                />
                <FileInput
                  className={formClasses.files}
                  value={files}
                  onChange={handleFilesChange}
                  multiple
                  accept=".jpg,.jpeg,image/jpeg"
                  label="Scanner JPEGs"
                  placeholder="Select files"
                  clearable
                  disabled={creating}
                  required
                />
                <Button
                  className={formClasses.create}
                  onClick={handleCreate}
                  disabled={
                    creating ||
                    game === null ||
                    finish === null ||
                    sortedFiles.length === 0 ||
                    fileErrors.length > 0
                  }
                  loading={creating}
                >
                  Create scan
                </Button>
              </div>
              <Text size="xs" c="dimmed">
                Files are sorted by filename from bottom to top.
              </Text>
              {files.length > 0 && fileErrors.length > 0 && (
                <Stack gap={2} role="alert">
                  {fileErrors.map((fileError) => (
                    <Text key={fileError} size="sm" c="red.7">
                      {fileError}
                    </Text>
                  ))}
                </Stack>
              )}
              {submitError && (
                <Text size="sm" c="red.7" role="alert">
                  {submitError}
                </Text>
              )}
            </Stack>
          }
          footer={
            nextContinuation ? (
              <Group justify="flex-start">
                <Button
                  variant="default"
                  onClick={handleLoadMore}
                  loading={loadingMore}
                  disabled={creating}
                >
                  Load more
                </Button>
              </Group>
            ) : undefined
          }
        >
          {loading && <CollectionLoadingState rows={4} />}
          {!loading && error && (
            <CollectionMessage
              title="Scans could not be loaded"
              description={error}
              tone="error"
            />
          )}
          {!loading && !error && scans.length === 0 && (
            <CollectionMessage
              title="No scans yet."
              description="Set up a new scan above to start a scanner intake."
            />
          )}
          {!loading && !error && scans.length > 0 && (
            <ScanTable scans={scans} onOpen={openScan} />
          )}
        </CollectionSurface>
      </Stack>
    </AppShellLayout>
  );
}
