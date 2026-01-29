import { useEffect, useState } from 'react';
import { Container, Stack, Skeleton, Text, Alert } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import { IconAlertCircle } from '@tabler/icons-react';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { ProgressSummary } from '../components/ProgressSummary';
import { ContentTypeSection } from '../components/ContentTypeSection';
import { apiClient } from '../api/client';
import {
  ProgressPresenter,
  type ProgressViewModel,
} from '../presenters/progress-presenter';

const presenter = new ProgressPresenter({ apiClient });

function LoadingSkeleton() {
  return (
    <Stack gap="xl">
      <Skeleton height={280} radius="md" />
      <Skeleton height={200} radius="md" />
      <Skeleton height={200} radius="md" />
      <Skeleton height={200} radius="md" />
      <Skeleton height={200} radius="md" />
    </Stack>
  );
}

export function ProgressPage() {
  const [progress, setProgress] = useState<ProgressViewModel | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const load = async () => {
      try {
        const data = await presenter.loadProgress();
        setProgress(data);
      } catch (e) {
        const message =
          e instanceof Error ? e.message : 'Failed to load progress';
        setError(message);
        notifications.show({
          title: 'Error',
          message,
          color: 'red',
        });
      } finally {
        setLoading(false);
      }
    };

    load();
  }, []);

  return (
    <AppShellLayout>
      <Container size="md" py="xl">
        {loading && <LoadingSkeleton />}

        {!loading && error && (
          <Alert icon={<IconAlertCircle size={16} />} title="Error" color="red">
            {error}
          </Alert>
        )}

        {!loading && !error && !progress && (
          <Text c="dimmed" ta="center">
            No progress data available
          </Text>
        )}

        {!loading && !error && progress && (
          <Stack gap="xl">
            <ProgressSummary
              summary={progress.summary}
              chartPoints={progress.chartPoints}
            />

            <ContentTypeSection
              section={progress.seriesSection}
              countLabel="episodes watched"
            />

            <ContentTypeSection
              section={progress.moviesSection}
              countLabel="movies watched"
            />

            <ContentTypeSection
              section={progress.youtubeSection}
              countLabel="videos watched"
            />

            <ContentTypeSection
              section={progress.spotifySection}
              countLabel="episodes listened"
            />
          </Stack>
        )}
      </Container>
    </AppShellLayout>
  );
}
