import { useEffect, useState } from 'react';
import { Button, FileInput, Group, Select, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import {
  CollectionLoadingState,
  CollectionMessage,
  CollectionSurface,
} from '../components/CollectionSurface';
import { ImportTable } from '../components/ImportTable';
import { PageHeader } from '../components/PageHeader';
import { apiClient } from '../api/client';
import type { GameId, ImportSummary } from '../api/client';
import { useGames } from '../GamesProvider';
import classes from './ImportsPage.module.css';

export function ImportsPage() {
  const { games } = useGames();
  const initialGame = games.find(
    ({ csv_import_enabled }) => csv_import_enabled,
  );
  const navigate = useNavigate();
  const [imports, setImports] = useState<ImportSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [game, setGame] = useState<GameId | null>(initialGame?.id ?? null);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  const selectedGame = games.find(({ id }) => id === game) ?? null;
  const canUpload = selectedGame?.csv_import_enabled ?? false;
  const importGames = games.filter(
    ({ csv_import_enabled }) => csv_import_enabled,
  );
  const openImport = (importSummary: ImportSummary) => {
    navigate(`/imports/${encodeURIComponent(importSummary.import_id)}`);
  };

  useEffect(() => {
    let cancelled = false;
    const fetchImports = async () => {
      try {
        const response = await apiClient.findImports();
        if (!cancelled) {
          setImports(response.imports);
          setNextContinuation(response.next_continuation);
        }
      } catch (e) {
        if (!cancelled) {
          const message =
            e instanceof Error ? e.message : 'Failed to load imports';
          setError(message);
          notifications.show({ title: 'Error', message, color: 'red' });
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
        }
      }
    };

    fetchImports();
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
      const response = await apiClient.findImports({
        continuation: nextContinuation,
      });
      setImports((previous) => [...previous, ...response.imports]);
      setNextContinuation(response.next_continuation);
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to load more imports';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setLoadingMore(false);
    }
  };

  const handleUpload = async () => {
    if (!file || !game || !canUpload) {
      return;
    }
    setUploading(true);
    try {
      const content = await file.text();
      const created = await apiClient.createImport(game, file.name, content);
      navigate(`/imports/${encodeURIComponent(created.import_id)}`);
    } catch (e) {
      const message = e instanceof Error ? e.message : 'Failed to upload CSV';
      notifications.show({ title: 'Upload failed', message, color: 'red' });
    } finally {
      setUploading(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader
          title="Imports"
          description="Upload collection CSV files and track each intake through appraisal and placement."
        />
        <CollectionSurface
          ariaLabel="Imports"
          toolbar={
            <Stack gap="xs">
              <div className={classes.importForm}>
                <Select
                  className={classes.game}
                  label="Game"
                  value={game}
                  onChange={(value) => {
                    const nextGame = value as GameId | null;
                    if (nextGame !== game) {
                      setFile(null);
                    }
                    setGame(nextGame);
                  }}
                  data={games.map(({ id, display_name }) => ({
                    value: id,
                    label: display_name,
                  }))}
                  placeholder="Select game"
                  required
                  disabled={uploading}
                />
                <FileInput
                  className={classes.file}
                  value={file}
                  onChange={setFile}
                  accept=".csv,text/csv"
                  label="CSV export"
                  placeholder={
                    canUpload
                      ? 'CSV export'
                      : game === null
                        ? 'Select game first'
                        : 'Unavailable for this game'
                  }
                  clearable
                  disabled={!canUpload || uploading}
                />
                <Button
                  className={classes.upload}
                  onClick={handleUpload}
                  disabled={!canUpload || !file}
                  loading={uploading}
                >
                  Upload
                </Button>
              </div>
              <Text size="xs" c="dimmed">
                {importGames.length > 0
                  ? `CSV imports are available for ${importGames
                      .map(({ display_name }) => display_name)
                      .join(', ')}.`
                  : 'CSV imports are unavailable for all registered games.'}
              </Text>
            </Stack>
          }
          footer={
            nextContinuation ? (
              <Group justify="flex-start">
                <Button
                  variant="default"
                  onClick={handleLoadMore}
                  loading={loadingMore}
                >
                  Load more
                </Button>
              </Group>
            ) : undefined
          }
        >
          {loading && <CollectionLoadingState rows={3} />}
          {!loading && error && (
            <CollectionMessage
              title="Imports could not be loaded"
              description={error}
              tone="error"
            />
          )}
          {!loading && !error && imports.length === 0 && (
            <CollectionMessage
              title="No imports yet."
              description="Choose a CSV file above to start an intake."
            />
          )}
          {!loading && !error && imports.length > 0 && (
            <ImportTable imports={imports} onOpen={openImport} />
          )}
        </CollectionSurface>
      </Stack>
    </AppShellLayout>
  );
}
