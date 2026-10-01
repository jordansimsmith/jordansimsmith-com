import { useEffect, useState } from 'react';
import { Button, Group, Paper, Progress, Stack, Text } from '@mantine/core';
import { notifications } from '@mantine/notifications';
import dayjs from 'dayjs';
import relativeTime from 'dayjs/plugin/relativeTime';
import { AppShellLayout } from '../layouts/AppShellLayout';
import { PageHeader } from '../components/PageHeader';
import { JobFailureAlert } from '../components/JobFailureAlert';
import { usePublishStatus } from '../PublishStatusProvider';

dayjs.extend(relativeTime);

const POLL_INTERVAL_MS = 2000;

export function PublishPage() {
  const publishStatus = usePublishStatus();
  const refresh = publishStatus?.refresh;
  const [triggering, setTriggering] = useState(false);
  const publish = publishStatus?.publish ?? null;
  const runActive =
    publish?.status === 'queued' || publish?.status === 'running';

  useEffect(() => {
    if (!runActive || !refresh) {
      return;
    }

    const timer = window.setTimeout(() => {
      void refresh();
    }, POLL_INTERVAL_MS);
    return () => window.clearTimeout(timer);
  }, [publish, refresh, runActive]);

  const latestPublished =
    publish?.status === 'succeeded' && publish.finished_at !== null
      ? dayjs(publish.finished_at * 1000).fromNow()
      : publish?.status === null
        ? 'Never'
        : 'Unavailable';

  const handlePublish = async () => {
    if (!publishStatus) {
      return;
    }
    setTriggering(true);
    try {
      await publishStatus.startPublish();
    } catch (e) {
      const message =
        e instanceof Error ? e.message : 'Failed to start publish';
      notifications.show({ title: 'Error', message, color: 'red' });
    } finally {
      setTriggering(false);
    }
  };

  return (
    <AppShellLayout>
      <Stack gap="lg">
        <PageHeader title="Publish" />
        <Paper
          component="section"
          aria-label="Publish summary"
          withBorder
          p="md"
          radius="md"
        >
          <Stack gap="md">
            <Group justify="space-between" align="flex-start" gap="md">
              <Stack gap={4}>
                <Text size="sm" c="dimmed">
                  Last published
                </Text>
                <Text fw={600} aria-live="polite">
                  {publish === null && publishStatus?.error === null
                    ? 'Loading publish status…'
                    : latestPublished}
                </Text>
              </Stack>
              <Button
                onClick={handlePublish}
                disabled={publish === null || runActive}
                loading={triggering}
              >
                {runActive ? 'Publishing' : 'Publish'}
              </Button>
            </Group>

            {publishStatus?.error && (
              <Group justify="space-between" align="center" gap="md">
                <Text size="sm" c="red" role="alert">
                  {publishStatus.error}
                </Text>
                <Button
                  variant="default"
                  size="compact-sm"
                  onClick={() => void publishStatus.refresh()}
                >
                  Retry status
                </Button>
              </Group>
            )}

            {publish?.status === 'failed' && (
              <JobFailureAlert title="Publish failed" error={publish.error} />
            )}

            {publish && (
              <Text size="sm" c="dimmed">
                Pending SKUs: {publish.pending_sku_count}
              </Text>
            )}

            {runActive && publish && (
              <Stack gap={4}>
                <Text size="sm" aria-live="polite">
                  Publishing {publish.published_sku_count} of{' '}
                  {publish.total_sku_count} SKUs
                </Text>
                <Progress
                  value={
                    publish.total_sku_count > 0
                      ? (publish.published_sku_count /
                          publish.total_sku_count) *
                        100
                      : 100
                  }
                  animated
                />
              </Stack>
            )}
          </Stack>
        </Paper>
      </Stack>
    </AppShellLayout>
  );
}
