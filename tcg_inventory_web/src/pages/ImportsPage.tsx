import { useEffect, useState } from 'react';
import { Button, FileInput, Group, Stack } from '@mantine/core';
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
import type { ImportSummary } from '../api/client';
import { parseManaBoxCsv } from '../domain/manabox';

export function ImportsPage() {
  const navigate = useNavigate();
  const [imports, setImports] = useState<ImportSummary[]>([]);
  const [nextContinuation, setNextContinuation] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
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
    if (!file) {
      return;
    }
    setUploading(true);
    try {
      const content = await file.text();
      parseManaBoxCsv(content);
      const created = await apiClient.createImport(file.name, content);
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
          description="Upload ManaBox exports and track each intake through appraisal and placement."
        />
        <CollectionSurface
          ariaLabel="Imports"
          toolbar={
            <Group align="flex-end" gap="sm" wrap="wrap">
              <FileInput
                value={file}
                onChange={setFile}
                accept=".csv,text/csv"
                label="ManaBox CSV export"
                placeholder="Select CSV"
                clearable
                style={{ flex: '1 1 16rem', maxWidth: 360 }}
              />
              <Button
                onClick={handleUpload}
                disabled={!file}
                loading={uploading}
              >
                Upload
              </Button>
            </Group>
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
              description="Choose a ManaBox CSV above to start an intake."
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
