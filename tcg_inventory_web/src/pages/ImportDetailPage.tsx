import { useEffect, useState } from 'react';
import {
  Alert,
  Badge,
  Button,
  Group,
  Progress,
  Skeleton,
  Stack,
  Text,
  Title,
} from '@mantine/core';
import { useNavigate, useParams } from 'react-router-dom';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { ImportStatusBadge } from '../components/ImportStatusBadge';
import { apiClient } from '../api/client';
import type { ImportDetail } from '../api/client';

const POLL_INTERVAL_MS = 2000;

export function ImportDetailPage() {
  const { importId } = useParams<{ importId: string }>();
  const navigate = useNavigate();
  const [importDetail, setImportDetail] = useState<ImportDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
      if (event.key !== 'Escape') {
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
  }, [navigate]);

  const appraised = importDetail
    ? importDetail.keep_count +
      importDetail.discard_count +
      importDetail.review_count
    : 0;

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
              <Alert color="red" title="Appraisal failed" maw={480}>
                {importDetail.appraisal_error}
              </Alert>
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
            {!importDetail.appraisal_error &&
              importDetail.status === 'review' && (
                <Text size="sm">Appraisal complete.</Text>
              )}
            <Group gap="sm">
              <Badge variant="light" color="green">
                Keep {importDetail.keep_count}
              </Badge>
              <Badge variant="light" color="gray">
                Discard {importDetail.discard_count}
              </Badge>
              <Badge variant="light" color="yellow">
                Review {importDetail.review_count}
              </Badge>
            </Group>
          </>
        )}
      </Stack>
    </AppShellLayout>
  );
}
