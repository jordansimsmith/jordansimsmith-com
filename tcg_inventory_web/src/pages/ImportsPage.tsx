import { useEffect, useRef, useState } from 'react';
import {
  Button,
  FileInput,
  Group,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { useNavigate } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { ImportTable } from '../components/ImportTable';
import { apiClient } from '../api/client';
import type { ImportSummary } from '../api/client';
import { parseManaBoxCsv } from '../domain/manabox';
import { useListNavigation } from '../hooks/use-list-navigation';

export function ImportsPage() {
  const navigate = useNavigate();
  const [imports, setImports] = useState<ImportSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [file, setFile] = useState<File | null>(null);
  const [uploading, setUploading] = useState(false);
  // this page has no search input; the ref keeps the navigation hook inert on "/"
  const searchInputRef = useRef<HTMLInputElement>(null);

  const openImport = (importSummary: ImportSummary) => {
    navigate(`/imports/${encodeURIComponent(importSummary.import_id)}`);
  };

  const { selectedIndex } = useListNavigation({
    itemCount: imports.length,
    onOpen: (index) => {
      const importSummary = imports[index];
      if (importSummary) {
        openImport(importSummary);
      }
    },
    searchInputRef,
  });

  useEffect(() => {
    let cancelled = false;
    const fetchImports = async () => {
      try {
        const response = await apiClient.findImports();
        if (!cancelled) {
          setImports(response.imports);
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
      <Stack gap="md">
        <Title order={2}>Imports</Title>
        <Group align="flex-end" gap="sm">
          <FileInput
            value={file}
            onChange={setFile}
            accept=".csv,text/csv"
            label="ManaBox CSV export"
            placeholder="Select file"
            clearable
            w={320}
          />
          <Button onClick={handleUpload} disabled={!file} loading={uploading}>
            Upload
          </Button>
        </Group>
        {loading && (
          <Stack gap="xs">
            {[1, 2, 3].map((row) => (
              <Skeleton key={row} height={28} />
            ))}
          </Stack>
        )}
        {!loading && error && (
          <Text c="red" ta="center">
            {error}
          </Text>
        )}
        {!loading && !error && imports.length === 0 && (
          <Text c="dimmed">No imports yet.</Text>
        )}
        {!loading && !error && imports.length > 0 && (
          <ImportTable
            imports={imports}
            selectedIndex={selectedIndex}
            onOpen={openImport}
          />
        )}
      </Stack>
    </AppShellLayout>
  );
}
